package net.kigawa.admin.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    val diskBays: DiskBayInfo = DiskBayInfo()
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
    val records = output.split(Regex("\\n\\s*\\n")).map { it.lines() }
    return records.mapNotNull { parseDmidecodeSlotRecord(it) }
}

/** dmidecode -t memory のMemory Deviceレコード1件をパースする。 */
internal fun parseDmidecodeMemoryRecord(lines: List<String>): MemorySlotInfo? {
    // DMI type 17 (Memory Device)のレコードのみ対象。-t memoryにはArray情報等の
    // 他レコードも混ざるため、2行目の種別で判定する。
    if (lines.getOrNull(1)?.trim() != "Memory Device") return null
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
    val records = output.split(Regex("\\n\\s*\\n")).map { it.lines() }
    return records.mapNotNull { parseDmidecodeMemoryRecord(it) }
}

/** lsblk --json 出力から物理ディスク一覧を取り出す。 */
internal fun parseLsblkJson(output: String): List<DiskBayDisk> {
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(output).jsonObject
        val devices = root["blockdevices"]?.jsonArray.orEmpty()
        devices.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "disk") return@mapNotNull null
            DiskBayDisk(
                name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                sizeBytes = obj["size"]?.let {
                    try {
                        it.jsonPrimitive.content.toLongOrNull()
                    } catch (e: IllegalArgumentException) {
                        // 数値でないsize表記の場合はnull
                        null
                    }
                },
                model = obj["model"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    } catch (e: Exception) {
        logger.warn("lsblk JSON parse failed: ${e.message}")
        emptyList()
    }
}

private suspend fun <T> withProxmoxSsh(hostIp: String, block: suspend (SSHClient) -> T): T =
    withContext(Dispatchers.IO) {
        val key = proxmoxSshPrivateKey
            ?.takeUnless { it.isBlank() || it.trim() == UNCONFIGURED_SENTINEL }
            ?: throw IOException("Proxmox SSH private key is not configured")
        SSHClient().use { client ->
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = SSH_CONNECT_TIMEOUT_MS
            client.connect(hostIp)
            val keyProvider: KeyProvider = client.loadKeys(key, null, null)
            client.authPublickey(proxmoxSshUser, keyProvider)
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
            "echo '${sudoPassword.replace("'", "'\\''")}' | sudo -S $command"
        } else {
            command
        }
        val cmd = session.exec(fullCommand)
        cmd.join(SSH_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = cmd.inputStream.bufferedReader().readText()
        val exitStatus = cmd.exitStatus
        if (exitStatus != null && exitStatus != 0) {
            val error = cmd.errorStream.bufferedReader().readText().trim().take(300)
            throw IOException("command failed (exit $exitStatus): $command${if (error.isNotBlank()) ": $error" else ""}")
        }
        return output
    }
}

/**
 * 指定Proxmoxホストの空きスロット調査を行う。SSH到達・認証・コマンド失敗時は
 * sshReachable=falseの空結果を返す(呼び出し側は既存のグレースフルデグラデーションで扱う)。
 */
suspend fun fetchHostSlotInventory(hostName: String): HostSlotInventoryDto {
    if (!isProxmoxSshConfigured) return HostSlotInventoryDto(sshReachable = false)
    val hostIp = proxmoxSshHosts[hostName] ?: return HostSlotInventoryDto(sshReachable = false)
    // root直ログイン時はsudo不要。root以外でsudoパスワード未設定の場合はdmidecodeが
    // 失敗し、空結果になる(呼び出し側でsshReachable=falseとして扱う)。
    val sudoPassword = if (proxmoxSshUser == "root") null else usableSudoPassword()

    return try {
        withTimeoutOrNull(SLOTS_OVERALL_TIMEOUT_MS) {
            withProxmoxSsh(hostIp) { client ->
                val slotsOut = execSshCommand(client, "dmidecode -t slot", sudoPassword)
                val memOut = execSshCommand(client, "dmidecode -t memory", sudoPassword)
                val lsblkOut = execSshCommand(client, "lsblk -d -b -o NAME,SIZE,MODEL,TYPE --json", sudoPassword)
                val populated = parseLsblkJson(lsblkOut)
                val totalBays = proxmoxDiskBays[hostName]
                HostSlotInventoryDto(
                    sshReachable = true,
                    pciSlots = parseDmidecodeSlots(slotsOut),
                    memorySlots = parseDmidecodeMemory(memOut),
                    diskBays = DiskBayInfo(
                        totalBays = totalBays,
                        populated = populated,
                        freeBays = totalBays?.let { (it - populated.size).coerceAtLeast(0) }
                    )
                )
            }
        } ?: HostSlotInventoryDto(sshReachable = false)
    } catch (e: Exception) {
        logger.warn("Proxmox slot inventory failed for $hostName: ${e::class.qualifiedName}: ${e.message}")
        HostSlotInventoryDto(sshReachable = false)
    }
}
