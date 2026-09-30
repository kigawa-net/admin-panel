package net.kigawa.admin.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("ProxmoxSshInventory")

/**
 * Proxmox物理ホストのデバイス空きスロット調査(admin-panel#156)。
 *
 * Proxmox APIには搭載デバイスの一覧しかなく、空きスロット(DMI System Slot、
 * 未実装DIMM、空きドライブベイ)は取得できない。そのためSSHでホストに直接入り、
 * dmidecode/lsblkで取得する。dmidecodeは/dev/memを読むためroot権限が必要で、
 * SSHユーザーがrootでない場合はsudoで昇格する(NodeSshOperationsと同様の考え方)。
 *
 * 認証情報はBitwardenから同期するSecret admin-panel-proxmox-ssh を参照する
 * (k8s/base/proxmox-ssh-bws.yaml)。未設定の間は機能全体が無効で、エンドポイントは
 * 503を返す(既存のオプショナル連携と同じフェイルセーフ)。
 */
private val proxmoxSshUser = System.getenv("PROXMOX_SSH_USER") ?: "root"
private val proxmoxSshPrivateKey = System.getenv("PROXMOX_SSH_PRIVATE_KEY")
private val proxmoxSshSudoPassword = System.getenv("PROXMOX_SSH_SUDO_PASSWORD")

/** Bitwardenアイテム作成直後の未設定番兵値。有効な鍵ではあり得ないため安全に無効判定できる。 */
private const val UNCONFIGURED_SENTINEL = "NOT_CONFIGURED"

val isProxmoxSshConfigured: Boolean
    get() {
        val key = proxmoxSshPrivateKey
        return !key.isNullOrBlank() && key.trim() != UNCONFIGURED_SENTINEL
    }

private fun usableSudoPassword(): String? {
    val password = proxmoxSshSudoPassword
    return if (password.isNullOrBlank() || password.trim() == UNCONFIGURED_SENTINEL) null else password
}

/** "host1=192.168.1.10,host4=192.168.1.40" 形式のホスト名→IPマッピング。 */
private fun parseHostMap(raw: String?): Map<String, String> =
    raw?.split(",").orEmpty()
        .map { it.trim() }
        .filter { it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        .filter { (name, ip) -> name.isNotBlank() && ip.isNotBlank() }

private val proxmoxSshHosts: Map<String, String> =
    parseHostMap(System.getenv("PROXMOX_SSH_HOSTS")).ifEmpty {
        mapOf("host1" to "192.168.1.10", "host4" to "192.168.1.40")
    }

/** "host1=4,host4=8" 形式のホスト名→総ドライブベイ数。機種依存のため未設定可。 */
private val proxmoxDiskBays: Map<String, Int> =
    parseHostMap(System.getenv("PROXMOX_DISK_BAYS")).mapNotNull { (name, total) ->
        total.toIntOrNull()?.let { name to it }
    }.toMap()

private const val SSH_CONNECT_TIMEOUT_MS = 10_000
private const val SSH_COMMAND_TIMEOUT_SECONDS = 30L
private const val SLOTS_OVERALL_TIMEOUT_MS = 45_000L

@Serializable
data class PciSlotInfo(
    /** スロット表記(例: "PCIEX16_1")。取れなければバスアドレス等で代替。 */
    val designation: String,
    /** スロット種別(例: "x16 PCI Express")。 */
    val type: String?,
    /** データバス幅(例: "16x / x16")。 */
    val width: String?,
    /** dmidecodeのCurrent Usage原文(例: "In Use" / "Available")。 */
    val usage: String?,
    /** 空きスロットかどうか(usage == Available)。 */
    val free: Boolean
)

@Serializable
data class MemorySlotInfo(
    /** ソケット表記(例: "DIMM_A1")。 */
    val locator: String,
    /** 実装サイズ(MB)。未実装はnull。 */
    val sizeMb: Long?,
    /** メモリ種別(例: "DDR4")。 */
    val memType: String?,
    /** 動作速度表記(例: "3200 MT/s")。 */
    val speed: String?,
    /** 空きスロットかどうか(未実装)。 */
    val free: Boolean
)

@Serializable
data class DiskBayDisk(
    val name: String,
    val sizeBytes: Long?,
    val model: String?
)

@Serializable
data class DiskBayInfo(
    /** 総ドライブベイ数。機種定義(PROXMOX_DISK_BAYS)がない場合はnull。 */
    val totalBays: Int? = null,
    val populated: List<DiskBayDisk> = emptyList(),
    /** 空きベイ数。総数が不明な場合はnull。 */
    val freeBays: Int? = null
)

@Serializable
data class HostSlotInventoryDto(
    /** SSH到達・取得に成功したか。falseの場合は各リストが空。 */
    val sshReachable: Boolean = true,
    val pciSlots: List<PciSlotInfo> = emptyList(),
    val memorySlots: List<MemorySlotInfo> = emptyList(),
    val diskBays: DiskBayInfo = DiskBayInfo(),
    /** 仮想マシン上と判定されたかどうか。真の場合は物理スロットの概念がない。 */
    val virtualized: Boolean = false,
    /** dmidecode -s system-product-name の原文(取得失敗時はnull)。 */
    val systemProduct: String? = null
)

/** dmidecode -t slot の1レコードからPCIスロット情報を取り出す。 */
internal fun parseDmidecodeSlotRecord(lines: List<String>): PciSlotInfo? {
    fun field(prefix: String): String? =
        lines.firstOrNull { it.trim().startsWith(prefix) }
            ?.trim()?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }

    val designation = field("Designation:")
        ?: field("Bus Address:")
        ?: return null
    val usage = field("Current Usage:")
    return PciSlotInfo(
        designation = designation,
        type = field("Type:"),
        width = field("Data Bus Width:"),
        usage = usage,
        free = usage?.equals("Available", ignoreCase = true) == true
    )
}

/** dmidecode -t slot 出力全体をパースする。 */
internal fun parseDmidecodeSlots(output: String): List<PciSlotInfo> {
    // SSH PTY(+sudo use_pty)経由では改行が多重化(\r\n\r\n等)されることが実機で
    // 確認されており、そのままだと空行だらけでレコード判定が崩れる。先に正規化する。
    val normalized = output.replace("\r\r\n", "\n").replace("\r\n", "\n").replace("\r", "\n")
    val records = normalized.split(Regex("\\n\\s*\\n")).map { it.lines() }
    return records.mapNotNull { parseDmidecodeSlotRecord(it) }
}

/** dmidecode -t memory のMemory Deviceレコード1件をパースする。 */
internal fun parseDmidecodeMemoryRecord(lines: List<String>): MemorySlotInfo? {
    // DMI type 17 (Memory Device)のレコードのみ対象。-t memoryにはArray情報等の
    // 他レコードも混ざるため、種別行で判定する。SSH PTY経由では改行が二重化
    // (\r\n)されて空行が混じることが実機で確認されたため、位置ではなく内容で判定する。
    if (lines.none { it.trim() == "Memory Device" }) return null
    fun field(prefix: String): String? =
        lines.firstOrNull { it.trim().startsWith(prefix) }
            ?.trim()?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }

    val sizeRaw = field("Size:") ?: return null
    val free = sizeRaw.equals("No Module Installed", ignoreCase = true)
    return MemorySlotInfo(
        locator = field("Locator:") ?: "Unknown",
        sizeMb = if (free) null else parseMemorySizeMb(sizeRaw),
        memType = field("Type:")?.takeUnless { it.equals("Unknown", ignoreCase = true) },
        speed = field("Speed:")?.takeUnless {
            it.equals("Unknown", ignoreCase = true) || it.equals("Undetermined", ignoreCase = true)
        },
        free = free
    )
}

/** "16 GB" / "1024 MB" / "1 TB" 等をMB数値に変換する。 */
internal fun parseMemorySizeMb(raw: String): Long? {
    val match = Regex("^([0-9]+)\\s*([KMGT]B)$", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    return when (match.groupValues[2].uppercase()) {
        "KB" -> value / 1024
        "MB" -> value
        "GB" -> value * 1024
        "TB" -> value * 1024 * 1024
        else -> null
    }
}

/** dmidecode -t memory 出力全体をパースする。 */
internal fun parseDmidecodeMemory(output: String): List<MemorySlotInfo> {
    // 改行多重化への対処はparseDmidecodeSlotsと同様。
    val normalized = output.replace("\r\r\n", "\n").replace("\r\n", "\n").replace("\r", "\n")
    val records = normalized.split(Regex("\\n\\s*\\n")).map { it.lines() }
    return records.mapNotNull { parseDmidecodeMemoryRecord(it) }
}

/** KVMゲストの仮想ディスク(nbd/rbd/loop/dm-/md等)の名前接頭辞。物理ディスク意味論のため除外する。 */
private val virtualDiskPrefixes = listOf("nbd", "rbd", "loop", "dm-", "md")

/** lsblk --json 出力から物理ディスク一覧を取り出す。 */
internal fun parseLsblkJson(output: String): List<DiskBayDisk> {
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(output).jsonObject
        val devices = root["blockdevices"]?.jsonArray.orEmpty()
        devices.mapNotNull { element ->
            val obj = element.jsonObject
            // JsonNullに対するjsonPrimitiveは例外になるため、欠損・nullは先に弾く
            val typeElement = obj["type"]
            if (typeElement == null || typeElement is JsonNull || typeElement.jsonPrimitive.content != "disk") {
                return@mapNotNull null
            }
            val nameElement = obj["name"]
            val name = if (nameElement == null || nameElement is JsonNull) {
                return@mapNotNull null
            } else {
                nameElement.jsonPrimitive.content
            }
            // 仮想デバイスは物理ディスクの意味論を持たないため除外する
            if (virtualDiskPrefixes.any { name.startsWith(it) }) return@mapNotNull null
            val sizeElement = obj["size"]
            val sizeBytes = if (sizeElement == null || sizeElement is JsonNull) {
                null
            } else {
                try {
                    sizeElement.jsonPrimitive.content.toLongOrNull()
                } catch (e: IllegalArgumentException) {
                    // 数値でないsize表記の場合はnull
                    null
                }
            }
            val modelElement = obj["model"]
            DiskBayDisk(
                name = name,
                sizeBytes = sizeBytes,
                model = if (modelElement == null || modelElement is JsonNull) {
                    null
                } else {
                    try {
                        modelElement.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            )
        }
    } catch (e: Exception) {
        logger.warn("lsblk JSON parse failed: ${e.message}")
        emptyList()
    }
}

/**
 * dmidecode -s system-product-name の出力から仮想マシン上かどうかを判定する。
 * 物理スロットの概念がないKVMゲスト等ではフロント側でスロット表示を抑止するために使う。
 */
internal fun isVirtualProductName(name: String): Boolean {
    val lower = name.lowercase()
    return listOf(
        "qemu",
        "kvm",
        "vmware",
        "virtualbox",
        "xen",
        "virtual machine",
        "hyper-v",
        "hyperv",
        "microsoft corporation",
        "ec2",
        "elastic compute",
        "google compute",
        "gce",
        "openstack",
        "bochs",
        "bhyve",
        "parallels",
        // QEMU系の定番プロダクト名。ManufacturerではなくProduct名に出る。
        "i440fx",
        "standard pc",
        "q35",
        "ich9"
    ).any { lower.contains(it) }
}

private suspend fun <T> withSshKey(hostIp: String, sshUser: String, privateKey: String, block: suspend (SSHClient) -> T): T =
    withContext(Dispatchers.IO) {
        SSHClient().use { client ->
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = SSH_CONNECT_TIMEOUT_MS
            client.connect(hostIp)
            val keyProvider: KeyProvider = client.loadKeys(privateKey, null, null)
            client.authPublickey(sshUser, keyProvider)
            block(client)
        }
    }

/** SSHセッションで1コマンド実行する(ブロッキング呼び出しのためIOディスパッチャ上で使う)。 */
private fun execSshCommand(client: SSHClient, command: String, sudoPassword: String?): String {
    client.startSession().use { session ->
        // sudoがrequiretty設定でTTY無しの実行を拒否する環境があるため、
        // execの前にPTYを割り当てる(NodeSshOperationsと同様)。
        session.allocatePTY("vt100", 80, 24, 0, 0, emptyMap())
        val fullCommand = if (sudoPassword != null) {
            // -p '' でパスワードプロンプトを空にし、stdoutへの混入を防ぐ
            "echo '${sudoPassword.replace("'", "'\\''")}' | sudo -S -p '' $command"
        } else {
            command
        }
        val cmd = session.exec(fullCommand)
        cmd.join(SSH_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = cmd.inputStream.bufferedReader().readText()
        val exitStatus = cmd.exitStatus
        // exitStatusが取れない(joinタイムアウト等)場合は成功とみなさず失敗扱いにする。
        // 不完全な出力を正常としてパースすると、空リスト等の欠けた結果を正常らしく
        // 返してしまい、原因切り分けが困難になる。
        if (exitStatus == null) {
            throw IOException("command exit status unknown (timed out?): $command")
        }
        // 診断用にコマンド単位の結果を記録する(出力本文はログに出さない)。
        logger.info("ssh command done: $command exit=$exitStatus bytes=${output.length}")
        if (exitStatus != 0) {
            val error = cmd.errorStream.bufferedReader().readText().trim().take(300)
            throw IOException("command failed (exit $exitStatus): $command${if (error.isNotBlank()) ": $error" else ""}")
        }
        return output
    }
}

/**
 * SSH接続+4コマンド実行+DTO組み立ての本体。Proxmoxホスト用・k8sノード用の
 * どちらからも使う共通処理。SSH到達・認証・コマンド失敗時は
 * sshReachable=falseの空結果を返す(呼び出し側は既存のグレースフルデグラデーションで扱う)。
 */
internal suspend fun fetchSlotInventoryViaSsh(
    label: String,
    hostIp: String,
    sshUser: String,
    privateKey: String,
    sudoPassword: String?,
    totalBays: Int?
): HostSlotInventoryDto {
    return try {
        withTimeoutOrNull(SLOTS_OVERALL_TIMEOUT_MS) {
            withSshKey(hostIp, sshUser, privateKey) { client ->
                val slotsOut = execSshCommand(client, "dmidecode -t slot", sudoPassword)
                val memOut = execSshCommand(client, "dmidecode -t memory", sudoPassword)
                val lsblkOut = execSshCommand(client, "lsblk -d -b -o NAME,SIZE,MODEL,TYPE --json", sudoPassword)
                // 機種名の取得だけ失敗しても全体は継続する(virtualized判定だけ欠ける)
                val productOut = try {
                    execSshCommand(client, "dmidecode -s system-product-name", sudoPassword).trim()
                        .takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    logger.warn("system-product-name query failed for $label: ${e.message}")
                    null
                }
                val populated = parseLsblkJson(lsblkOut)
                val pciSlots = parseDmidecodeSlots(slotsOut)
                val memSlots = parseDmidecodeMemory(memOut)
                if (memSlots.isEmpty() && memOut.isNotBlank()) {
                    // 内容自体(シリアル番号等が含まれる)は出さず、構造の手がかりだけ記録する
                    logger.warn(
                        "memory parse empty for $label: bytes=${memOut.length} " +
                            "lines=${memOut.lines().size} " +
                            "hasMemoryDevice=${memOut.contains("Memory Device")} " +
                            "hasHandle=${memOut.contains("Handle ")} " +
                            "hasCrlf=${memOut.contains("\r\n")} " +
                            "hasCr=${memOut.contains("\r")}"
                    )
                }
                HostSlotInventoryDto(
                    sshReachable = true,
                    pciSlots = pciSlots,
                    memorySlots = memSlots,
                    diskBays = DiskBayInfo(
                        totalBays = totalBays,
                        populated = populated,
                        freeBays = totalBays?.let { (it - populated.size).coerceAtLeast(0) }
                    ),
                    virtualized = productOut?.let { isVirtualProductName(it) } == true,
                    systemProduct = productOut
                )
            }
        } ?: HostSlotInventoryDto(sshReachable = false)
    } catch (e: Exception) {
        logger.warn("Slot inventory failed for $label: ${e::class.qualifiedName}: ${e.message}")
        HostSlotInventoryDto(sshReachable = false)
    }
}

/**
 * 指定Proxmoxホストの空きスロット調査を行う。SSH到達・認証・コマンド失敗時は
 * sshReachable=falseの空結果を返す(呼び出し側は既存のグレースフルデグラデーションで扱う)。
 */
suspend fun fetchHostSlotInventory(hostName: String): HostSlotInventoryDto {
    if (!isProxmoxSshConfigured) return HostSlotInventoryDto(sshReachable = false)
    val hostIp = proxmoxSshHosts[hostName] ?: return HostSlotInventoryDto(sshReachable = false)
    val key = proxmoxSshPrivateKey
        ?.takeUnless { it.isBlank() || it.trim() == UNCONFIGURED_SENTINEL }
        ?: return HostSlotInventoryDto(sshReachable = false)
    // root直ログイン時はsudo不要。root以外でsudoパスワード未設定の場合はdmidecodeが
    // 失敗し、空結果になる(呼び出し側でsshReachable=falseとして扱う)。
    val sudoPassword = if (proxmoxSshUser == "root") null else usableSudoPassword()
    return fetchSlotInventoryViaSsh(
        label = hostName,
        hostIp = hostIp,
        sshUser = proxmoxSshUser,
        privateKey = key,
        sudoPassword = sudoPassword,
        totalBays = proxmoxDiskBays[hostName]
    )
}

/**
 * マウントポイント別ディスク使用率(admin-panel#148)。
 *
 * Prometheusにnode-exporterがなく`node_filesystem_*`が存在しないこと、Proxmox APIが
 * ルートマウントのrootfsしか返さないことから、時系列グラフではなくSSH経由のdfで
 * 現在値を取得する。空きスロット調査(#156)とは独立した取得にしているため、フロントは
 * スロット取得と並列に叩ける(低速なカテゴリが他を道連れにしない方針#158と同じ)。
 */
@Serializable
data class DiskUsageDto(
    /** マウントポイント(例: "/")。 */
    val mountpoint: String,
    /** ファイルシステム総容量(バイト)。 */
    val sizeBytes: Long,
    /** 使用中(バイト)。 */
    val usedBytes: Long,
    /** 空き(バイト)。 */
    val availBytes: Long,
    /** 使用率(0〜100の整数。dfのCapacity列から "%" を除いた値)。 */
    val percent: Int
)

/**
 * dfの除外対象。tmpfs/devtmpfsは実体を持たない疑似FS、squashfsはスナップの読み取り専用FS、
 * overlayはコンテナランタイムが張る差分レイヤでホスト本体の使用率ではないため落とす。
 * 対象はホスト直下のSSHなので任意だが、フロントに無意味な行を並べないための設定。
 */
private const val DF_COMMAND = "df -B1 -P -x tmpfs -x devtmpfs -x squashfs -x overlay"

/** df -x が未対応のディストリ向けの保険(除外はパーサー側で行う)。 */
private const val DF_COMMAND_FALLBACK = "df -B1 -P"

/** df -x 非対応環境向けの保険として、パーサー側で落とす疑似FSのソース名。 */
private val pseudoFsSources = setOf("tmpfs", "devtmpfs", "udev", "overlay", "squashfs", "proc", "sysfs", "cgroup2")

/** df -x 非対応環境向けの保険として、実使用率に意味を持たない疑似FSのマウントポイント群。 */
private val pseudoFsMountPoints = listOf("/proc", "/sys", "/snap")

private const val DISK_USAGE_OVERALL_TIMEOUT_MS = 30_000L

/**
 * `df -B1 -P`の出力をパースする。
 *
 * - ヘッダ行・数値でない行(エラー行等)は読み飛ばす
 * - `-P`でも同一マウントポイントが重複出力されることがあるため先勝ちで重複を落とす
 * - 同一デバイスの多重マウント(k8sノードのbind mount等)は使用率が同じなので先勝ちで落とす
 * - マウントポイントに空白が含まれることがあるため、6列目以降は結合して扱う
 * - PTY経由の改行多重化はparseDmidecodeSlotsと同じ正規化で吸収する
 */
internal fun parseDfUsage(output: String): List<DiskUsageDto> {
    val normalized = output.replace("\r\r\n", "\n").replace("\r\n", "\n").replace("\r", "\n")
    val result = mutableListOf<DiskUsageDto>()
    val seenMountPoints = mutableSetOf<String>()
    val seenSources = mutableSetOf<String>()
    for (rawLine in normalized.lines()) {
        val columns = rawLine.trim().split(Regex("\\s+"))
        // 列: Filesystem / 1B-blocks / Used / Available / Capacity / Mounted on
        if (columns.size < 6) continue
        val sizeBytes = columns[1].toLongOrNull() ?: continue
        val usedBytes = columns[2].toLongOrNull() ?: continue
        val availBytes = columns[3].toLongOrNull() ?: continue
        val percent = columns[4].removeSuffix("%").toIntOrNull() ?: continue
        val mountPoint = columns.drop(5).joinToString(" ").trim()
        if (mountPoint.isEmpty() || sizeBytes <= 0) continue
        // df -x が効かない環境での保険(上のコマンドコメント参照)
        if (columns[0] in pseudoFsSources) continue
        if (pseudoFsMountPoints.any { mountPoint == it || mountPoint.startsWith("$it/") }) continue
        if (!seenMountPoints.add(mountPoint)) continue
        if (!seenSources.add(columns[0])) continue
        result.add(
            DiskUsageDto(
                mountpoint = mountPoint,
                sizeBytes = sizeBytes,
                usedBytes = usedBytes,
                availBytes = availBytes,
                percent = percent
            )
        )
    }
    return result
}

/**
 * SSHでdfを実行しマウントポイント別ディスク使用率を組み立てる。
 * SSH到達・認証・コマンド失敗時は空リストを返す(グレースフルデグラデーション)。
 */
internal suspend fun fetchDiskUsageViaSsh(
    label: String,
    hostIp: String,
    sshUser: String,
    privateKey: String,
    sudoPassword: String?
): List<DiskUsageDto> {
    return try {
        withTimeoutOrNull(DISK_USAGE_OVERALL_TIMEOUT_MS) {
            withSshKey(hostIp, sshUser, privateKey) { client ->
                val output = try {
                    execSshCommand(client, DF_COMMAND, sudoPassword)
                } catch (e: IOException) {
                    // df -x 未対応のディストリではexit!=0で落ちるため、素のdfで再試行し
                    // 疑似FSの除外はパーサー側の保険で行う。コマンド都合の再試行なので
                    // ここだけは例外を握りつぶして続行する。
                    logger.warn("df -x failed for $label, retrying without exclusions: ${e.message}")
                    execSshCommand(client, DF_COMMAND_FALLBACK, sudoPassword)
                }
                parseDfUsage(output)
            }
        } ?: emptyList()
    } catch (e: Throwable) {
        logger.warn("Disk usage failed for $label: ${e::class.qualifiedName}: ${e.message}")
        emptyList()
    }
}

/**
 * 指定Proxmoxホストのディスク使用率を取得する。SSH未設定・ホスト不明・失敗時は空リスト。
 */
suspend fun fetchHostDiskUsage(hostName: String): List<DiskUsageDto> {
    if (!isProxmoxSshConfigured) return emptyList()
    val hostIp = proxmoxSshHosts[hostName] ?: return emptyList()
    val key = proxmoxSshPrivateKey
        ?.takeUnless { it.isBlank() || it.trim() == UNCONFIGURED_SENTINEL }
        ?: return emptyList()
    // df自体はroot不要だが、非rootでは一部マウントポイントが読めないことがあるため、
    // スロット取得と同じ経路(sudo昇格あり)で実行して結果を揃える。
    val sudoPassword = if (proxmoxSshUser == "root") null else usableSudoPassword()
    return fetchDiskUsageViaSsh(
        label = hostName,
        hostIp = hostIp,
        sshUser = proxmoxSshUser,
        privateKey = key,
        sudoPassword = sudoPassword
    )
}

/**
 * 指定k8sノードのディスク使用率を取得する。ノードIPはKubernetes APIのInternalIPで解決し、
 * 認証情報はNODE_SSH_*を使う。IP不明・未設定・失敗時は空リスト。
 */
suspend fun fetchNodeDiskUsage(nodeName: String): List<DiskUsageDto> {
    if (!isNodeSshConfigured) return emptyList()
    val hostIp = getNodeInternalIp(nodeName) ?: return emptyList()
    val key = System.getenv("NODE_SSH_PRIVATE_KEY")
        ?.takeUnless { it.isBlank() || it.trim() == UNCONFIGURED_SENTINEL }
        ?: return emptyList()
    val sshUser = System.getenv("NODE_SSH_USER")?.takeIf { it.isNotBlank() } ?: "kigawa"
    return fetchDiskUsageViaSsh(
        label = nodeName,
        hostIp = hostIp,
        sshUser = sshUser,
        privateKey = key,
        sudoPassword = usableNodeSudoPassword()
    )
}

/** k8sノード用SSH認証情報(NODE_SSH_PRIVATE_KEY)の設定有無。未設定の間はノード向け機能全体が無効。 */
val isNodeSshConfigured: Boolean
    get() {
        val key = System.getenv("NODE_SSH_PRIVATE_KEY")
        return !key.isNullOrBlank() && key.trim() != UNCONFIGURED_SENTINEL
    }

private fun usableNodeSudoPassword(): String? {
    val password = System.getenv("NODE_SSH_SUDO_PASSWORD")
    return if (password.isNullOrBlank() || password.trim() == UNCONFIGURED_SENTINEL) null else password
}

/**
 * 指定k8sノードの空きスロット調査を行う。ノードIPはKubernetes APIのInternalIPで
 * 解決し、認証情報はNODE_SSH_*を使う。総ドライブベイ数は機種定義がないためnull。
 * IP不明・未設定時はsshReachable=falseを返す。
 */
suspend fun fetchNodeSlotInventory(nodeName: String): HostSlotInventoryDto {
    if (!isNodeSshConfigured) return HostSlotInventoryDto(sshReachable = false)
    val hostIp = getNodeInternalIp(nodeName) ?: return HostSlotInventoryDto(sshReachable = false)
    val key = System.getenv("NODE_SSH_PRIVATE_KEY")
        ?.takeUnless { it.isBlank() || it.trim() == UNCONFIGURED_SENTINEL }
        ?: return HostSlotInventoryDto(sshReachable = false)
    val sshUser = System.getenv("NODE_SSH_USER")?.takeIf { it.isNotBlank() } ?: "kigawa"
    return fetchSlotInventoryViaSsh(
        label = nodeName,
        hostIp = hostIp,
        sshUser = sshUser,
        privateKey = key,
        sudoPassword = usableNodeSudoPassword(),
        totalBays = null
    )
}
