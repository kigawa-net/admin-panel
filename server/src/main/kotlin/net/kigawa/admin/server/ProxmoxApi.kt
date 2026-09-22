package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

private val logger = LoggerFactory.getLogger("ProxmoxApi")

// system-proxmoxのExternalName Service(proxmox-service)はexternalNameに生IPを設定して
// いるため、DNS仕様上CNAMEとして解決できずCoreDNSからNXDOMAINが返る(要修正はこのリポジトリの
// 範囲外)。回避策としてそのIPを直接指定する。
//
// 接続先はhost4(192.168.1.40)ではなくhost1(192.168.1.10)を指定している。Proxmoxは
// クラスタ内のどのノードのpveproxyに接続しても同じクラスタ全体のデータ(pmxcfs経由で
// レプリケートされている)が返るが、host4はゲストVM(特にk8s-worker4)による慢性的な
// CPU逼迫で自身のpveproxy/pvedaemonの応答が断続的に20秒以上遅延することを実機で確認
// した。host1はCPUに余裕があり(load average 2前後)、同じ`nodes`一覧を1秒未満で返す
// ため、host4に直接繋ぐより大幅に安定する。ノード個別のqemu呼び出し(例:
// nodes/host4/qemu)はhost1のpveproxyがhost4へ内部的にプロキシするため引き続き
// host4自体の遅さの影響を受けるが、最も頻繁に失敗していた`nodes`一覧取得はこれで
// 解消される見込み。
private val proxmoxApiUrl =
    System.getenv("PROXMOX_API_URL") ?: "https://192.168.1.10:8006"
private val proxmoxTokenId = System.getenv("PROXMOX_API_TOKEN_ID")
private val proxmoxTokenSecret = System.getenv("PROXMOX_API_TOKEN_SECRET")

@Serializable
private data class ProxmoxEnvelope<T>(val data: T)

@Serializable
data class ProxmoxNodeDto(
    val node: String,
    val status: String,
    val maxcpu: Int? = null,
    val maxmem: Long? = null,
    val uptime: Long? = null
)

@Serializable
data class ProxmoxVmDto(
    val vmid: Int,
    val name: String? = null,
    val status: String,
    val cpus: Int? = null,
    val maxmem: Long? = null,
    val uptime: Long? = null
)

@Serializable
data class ProxmoxCpuInfoDto(
    val model: String? = null,
    val sockets: Int? = null,
    val cores: Int? = null
)

@Serializable
data class ProxmoxRootfsDto(
    val total: Long? = null,
    val used: Long? = null
)

@Serializable
data class ProxmoxNodeStatusDto(
    val cpuinfo: ProxmoxCpuInfoDto? = null,
    val kversion: String? = null,
    val pveversion: String? = null,
    val rootfs: ProxmoxRootfsDto? = null
)

/** rpm/wearoutはディスク種別によって数値/"N/A"文字列が混在して返るためあえて含めない
 * (kotlinx.serializationで型が固定のフィールドとして扱えず、一覧全体のデシリアライズが
 * 失敗してしまうため)。型番の把握に必要な項目のみ抽出する。 */
@Serializable
data class ProxmoxDiskDto(
    val devpath: String? = null,
    val model: String? = null,
    val vendor: String? = null,
    val type: String? = null,
    val size: Long? = null,
    val health: String? = null
)

@Serializable
data class ProxmoxPciDeviceDto(
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("class") val pciClass: String? = null
)

@Serializable
data class InfraDiskDto(
    val devpath: String,
    val model: String,
    val type: String,
    val sizeBytes: Long?,
    val health: String?
)

@Serializable
data class InfraPciDeviceDto(
    val name: String,
    val vendor: String?
)

@Serializable
data class InfraVmDto(
    val vmid: Int,
    val name: String,
    val status: String,
    val cpuCores: Int?,
    val memoryBytes: Long?,
    val matchedNode: ServerStatusDto? = null
)

/**
 * ホストの基本情報+ハードウェア情報(nodes一覧+ノードごとのstatus呼び出しのみ)。
 * VM一覧・ディスク・PCIデバイスはさらに呼び出し回数が多く遅くなりがちなため、
 * InfraHostDetailsDto側に分離し、ページはまずこちらを先に表示してから詳細を
 * 非同期で読み込めるようにしている。
 */
@Serializable
data class InfraHostDto(
    val name: String,
    val online: Boolean,
    val cpuCores: Int? = null,
    val memoryBytes: Long? = null,
    /** 以下はnodes/{node}/statusから取得する詳細なハードウェア情報。オフラインノードや
     * 取得失敗時はnullのままで、既存のcpuCores/memoryBytesのみの表示にフォールバックする。 */
    val cpuModel: String? = null,
    val cpuSockets: Int? = null,
    val cpuPhysicalCores: Int? = null,
    val kernelVersion: String? = null,
    val pveVersion: String? = null,
    val rootfsTotalBytes: Long? = null,
    val rootfsUsedBytes: Long? = null
)

@Serializable
data class InfrastructureTopologyDto(
    val proxmoxConfigured: Boolean,
    /** Proxmox APIのトークンは設定されているが、呼び出しが失敗した(到達不能・認証エラー等)場合false。
     * hostsが空のときにこれで区別しないと、クライアント側で「本当に0台」なのか
     * 「取得に失敗した」のか判別できず、何も表示されない画面になってしまう。 */
    val proxmoxReachable: Boolean = true,
    val hosts: List<InfraHostDto> = emptyList()
)

/** ホスト名をキーにしたVM/ディスク/PCIデバイスの詳細。取得失敗時は該当ホストのエントリ
 * ごと欠落する(クライアント側は「読み込み中」のまま扱う)。 */
@Serializable
data class InfraHostDetailsDto(
    val disks: List<InfraDiskDto> = emptyList(),
    /** 搭載済み拡張PCIデバイス一覧(nodes/{node}/hardware/pci)。チップセット内蔵の
     * コントローラ類は多すぎて有用でないため、ネットワーク/ストレージ/GPU相当の
     * デバイスのみに絞り込んで返す。取得失敗時は空リスト。 */
    val pciDevices: List<InfraPciDeviceDto> = emptyList(),
    val vms: List<InfraVmDto> = emptyList()
)

@Serializable
data class InfrastructureDetailsDto(
    val hostDetails: Map<String, InfraHostDetailsDto> = emptyMap(),
    /** ProxmoxのVMとして見つからなかったK8sノード(独立した物理マシン上で直接動作していると推定される)。 */
    val standaloneNodes: List<ServerStatusDto> = emptyList()
)

/**
 * Proxmoxは自己署名証明書のため、専用クライアントでのみ証明書検証を無効化する(共有httpClientには
 * 影響させない)。クラスタ内部の ExternalName Service 経由でのみ通信するため許容している。
 */
private fun buildProxmoxHttpClient(): HttpClient {
    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    return HttpClient(CIO) {
        engine {
            https {
                trustManager = trustAllManager
            }
            // 以前ここでendpoint.keepAliveTime = 0を設定し、接続の使い回し(keep-alive)
            // 自体を無効化しようとしていた。しかしKtor CIOの実装(ConnectionPipeline.kt)
            // を確認したところ、keepAliveTimeは内部で
            // `withTimeoutOrNull(keepAliveTime) { tasks.receive() }`という形で
            // リクエスト処理ループ自体の待機タイムアウトとして使われており、0を渡すと
            // このタイムアウトが実質ゼロになって正常にリクエストを処理できなくなる
            // (=接続の使い回しどころか、単発のリクエスト処理自体が不安定になる)ことが
            // 判明した。実機で見えていたEOFException/タイムアウトの一部は、Proxmox側では
            // なくこの設定自体が原因だった可能性が高い。デフォルト値(5000ms)に戻す。
        }
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // タイムアウト未設定だとProxmoxが応答しない場合にリクエストが無期限にハング
        // し、/api/infrastructure全体がCloudflareの524(オリジンタイムアウト)を
        // 引き起こしてしまう(実機で発生を確認)。
        install(HttpTimeout) {
            // 実測でProxmoxへの単発呼び出しに4秒以上かかるケースが確認されており、5秒の
            // connectTimeoutでは余裕が少なすぎた(このエンドポイントはnodes呼び出しに続けて
            // オンラインホストごとにqemu呼び出しを行うため、複数回の呼び出しのいずれか一つが
            // 詰まるだけで画面全体が「接続できませんでした」になっていた)。524を防ぐ元々の
            // 目的は保ちつつ、より現実的な値に緩和する。
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }
}

private fun authHeader(): String? {
    val tokenId = proxmoxTokenId ?: return null
    val tokenSecret = proxmoxTokenSecret ?: return null
    return "PVEAPIToken=$tokenId=$tokenSecret"
}

/**
 * ログでHttpRequestTimeoutExceptionが本番稼働中に断続的に発生することを確認済み(手動での
 * wget/生JVM再現は常に成功する一方、実運用では時折20秒のタイムアウトに達する)。実機で
 * 「20秒タイムアウトの直後(数十秒以内)には正常応答に戻っている」ことを繰り返し確認して
 * おり、host4側の瞬断は数十秒程度で自然に回復する短時間のものだと分かった。300msの間隔
 * ではこの回復を待つには短すぎたため、5秒に延ばして再試行の成功率を上げる。
 *
 * java.io.EOFException(接続が応答途中で切断される)も同様に一過性の事象として実機で
 * 確認されているが、これまでHttpRequestTimeoutExceptionのみをリトライ対象としており
 * EOFExceptionは初回失敗時点で即座に諦めてしまっていた。IOException全般を対象に含める
 * ことでこの抜け穴を塞ぐ。
 */
private suspend fun <T> withTimeoutRetry(description: String, block: suspend () -> T): T {
    try {
        return block()
    } catch (e: Exception) {
        if (e !is HttpRequestTimeoutException && e !is IOException) throw e
        logger.warn("$description failed on first attempt, retrying once: ${e::class.qualifiedName}: ${e.message}")
        delay(5_000)
        return block()
    }
}

private suspend fun fetchNodes(client: HttpClient, auth: String, description: String): List<ProxmoxNodeDto> =
    withTimeoutRetry(description) {
        client.get("$proxmoxApiUrl/api2/json/nodes") {
            header("Authorization", auth)
        }.body<ProxmoxEnvelope<List<ProxmoxNodeDto>>>().data
    }

/**
 * ホスト一覧+ハードウェア情報のみを取得する高速パス。ページはまずこちらの結果を表示し、
 * VM/ディスク/PCIデバイスの詳細はfetchInfrastructureDetails()で非同期に取得する。
 * ノードごとのstatus呼び出しは並列実行し、ノード数が増えても直列に待たされないようにする。
 */
suspend fun fetchInfrastructureHosts(): InfrastructureTopologyDto {
    val auth = authHeader() ?: return InfrastructureTopologyDto(proxmoxConfigured = false)

    // issue #118「インフラ構成ページが重い」の調査用に、実際の所要時間を記録する。
    val start = System.currentTimeMillis()
    val client = buildProxmoxHttpClient()
    try {
        val nodes = try {
            fetchNodes(client, auth, "Proxmox nodes fetch")
        } catch (e: Exception) {
            // 実機でwget/生JVM HttpsURLConnectionでの再現テストは常に成功するのに対し、
            // このKtor CIOクライアント経由の呼び出しだけが失敗し続けるという原因不明の
            // 事象が報告されているため、実際の例外を記録して次回発生時に追えるようにする。
            logger.warn("Proxmox nodes fetch failed: ${e::class.qualifiedName}: ${e.message}", e)
            return InfrastructureTopologyDto(proxmoxConfigured = true, proxmoxReachable = false, hosts = emptyList())
        }

        val hosts = coroutineScope {
            nodes.sortedBy { it.node }.map { node ->
                async {
                    // ハードウェア詳細(CPUモデル・カーネル/PVEバージョン・rootfs)はnodes一覧には
                    // 含まれないため、ノードごとにstatusを追加で取得する。取得失敗時は既存の
                    // cpuCores/memoryBytesのみの表示にフォールバックし、ページ全体は失敗させない。
                    val hwStatus = if (node.status == "online") {
                        try {
                            withTimeoutRetry("Proxmox status fetch for node ${node.node}") {
                                client.get("$proxmoxApiUrl/api2/json/nodes/${node.node}/status") {
                                    header("Authorization", auth)
                                }.body<ProxmoxEnvelope<ProxmoxNodeStatusDto>>().data
                            }
                        } catch (e: Exception) {
                            logger.warn("Proxmox status fetch failed for node ${node.node}: ${e::class.qualifiedName}: ${e.message}", e)
                            null
                        }
                    } else {
                        null
                    }

                    InfraHostDto(
                        name = node.node,
                        online = node.status == "online",
                        cpuCores = node.maxcpu,
                        memoryBytes = node.maxmem,
                        cpuModel = hwStatus?.cpuinfo?.model,
                        cpuSockets = hwStatus?.cpuinfo?.sockets,
                        cpuPhysicalCores = hwStatus?.cpuinfo?.cores,
                        kernelVersion = hwStatus?.kversion,
                        pveVersion = hwStatus?.pveversion,
                        rootfsTotalBytes = hwStatus?.rootfs?.total,
                        rootfsUsedBytes = hwStatus?.rootfs?.used
                    )
                }
            }.awaitAll()
        }

        return InfrastructureTopologyDto(proxmoxConfigured = true, hosts = hosts)
    } finally {
        client.close()
        logger.info("fetchInfrastructureHosts took ${System.currentTimeMillis() - start}ms")
    }
}

/**
 * VM一覧・ディスク・PCIデバイスの詳細をホストごとに並列取得する低速パス。
 * fetchInfrastructureHosts()とは別のHTTPクライアント/nodes呼び出しを使うため、こちらが
 * 失敗してもホスト一覧の表示自体には影響しない(該当ホストの詳細が空のまま残るのみ)。
 */
suspend fun fetchInfrastructureDetails(): InfrastructureDetailsDto {
    val auth = authHeader() ?: return InfrastructureDetailsDto()

    // issue #118「インフラ構成ページが重い」の調査用に、実際の所要時間を記録する。
    val start = System.currentTimeMillis()
    val client = buildProxmoxHttpClient()
    try {
        val nodes = try {
            fetchNodes(client, auth, "Proxmox nodes fetch (details)")
        } catch (e: Exception) {
            logger.warn("Proxmox nodes fetch failed (details): ${e::class.qualifiedName}: ${e.message}", e)
            return InfrastructureDetailsDto()
        }

        val k8sNodesByName = fetchServerStatuses()?.servers?.associateBy { it.name } ?: emptyMap()

        val hostDetailsList = coroutineScope {
            nodes.filter { it.status == "online" }.map { node ->
                async {
                    node.node to fetchHostDetails(client, auth, node.node, k8sNodesByName)
                }
            }.awaitAll()
        }

        val matchedNodeNames = hostDetailsList
            .flatMap { (_, details) -> details.vms }
            .mapNotNull { it.matchedNode?.name }
            .toSet()
        val standaloneNodes = k8sNodesByName.values.filter { it.name !in matchedNodeNames }.sortedBy { it.name }

        return InfrastructureDetailsDto(hostDetails = hostDetailsList.toMap(), standaloneNodes = standaloneNodes)
    } finally {
        client.close()
        logger.info("fetchInfrastructureDetails took ${System.currentTimeMillis() - start}ms")
    }
}

private suspend fun fetchHostDetails(
    client: HttpClient,
    auth: String,
    nodeName: String,
    k8sNodesByName: Map<String, ServerStatusDto>
): InfraHostDetailsDto = coroutineScope {
    val vmsDeferred = async {
        try {
            withTimeoutRetry("Proxmox qemu fetch for node $nodeName") {
                client.get("$proxmoxApiUrl/api2/json/nodes/$nodeName/qemu") {
                    header("Authorization", auth)
                }.body<ProxmoxEnvelope<List<ProxmoxVmDto>>>().data
            }
        } catch (e: Exception) {
            logger.warn("Proxmox qemu fetch failed for node $nodeName: ${e::class.qualifiedName}: ${e.message}", e)
            emptyList()
        }
    }

    // ディスク型番・搭載PCIデバイスは変化の少ない付随情報のため、あえてリトライなし・
    // 単発タイムアウト(20秒)のみとし、失敗時は空リストにフォールバックする。
    val disksDeferred = async {
        try {
            client.get("$proxmoxApiUrl/api2/json/nodes/$nodeName/disks/list") {
                header("Authorization", auth)
            }.body<ProxmoxEnvelope<List<ProxmoxDiskDto>>>().data
                .filter { it.model != null && it.devpath != null }
                .map { disk ->
                    InfraDiskDto(
                        devpath = disk.devpath!!,
                        model = disk.model!!,
                        type = disk.type ?: "unknown",
                        sizeBytes = disk.size,
                        health = disk.health
                    )
                }
        } catch (e: Exception) {
            logger.warn("Proxmox disks fetch failed for node $nodeName: ${e::class.qualifiedName}: ${e.message}")
            emptyList()
        }
    }

    val pciDeferred = async {
        try {
            client.get("$proxmoxApiUrl/api2/json/nodes/$nodeName/hardware/pci") {
                header("Authorization", auth)
            }.body<ProxmoxEnvelope<List<ProxmoxPciDeviceDto>>>().data
                // チップセット内蔵のUSB/SMBus/Thermal等のコントローラは大量にあり
                // 有用でないため、ネットワーク(0x02)・ストレージ(0x0108のNVMe含む)・
                // ディスプレイ(0x03)クラスのデバイスのみに絞り込む。
                .filter { device ->
                    val cls = device.pciClass?.removePrefix("0x") ?: return@filter false
                    cls.startsWith("02") || cls.startsWith("03") || cls.startsWith("0108")
                }
                .mapNotNull { device ->
                    val name = device.deviceName ?: return@mapNotNull null
                    InfraPciDeviceDto(name = name, vendor = device.vendorName)
                }
        } catch (e: Exception) {
            logger.warn("Proxmox PCI fetch failed for node $nodeName: ${e::class.qualifiedName}: ${e.message}")
            emptyList()
        }
    }

    val vms = vmsDeferred.await()
        .filter { it.status == "running" }
        .sortedBy { it.name ?: it.vmid.toString() }
        .map { vm ->
            val matched = vm.name?.let { k8sNodesByName[it] }
            InfraVmDto(
                vmid = vm.vmid,
                name = vm.name ?: "vm-${vm.vmid}",
                status = vm.status,
                cpuCores = vm.cpus,
                memoryBytes = vm.maxmem,
                matchedNode = matched
            )
        }

    InfraHostDetailsDto(disks = disksDeferred.await(), pciDevices = pciDeferred.await(), vms = vms)
}
