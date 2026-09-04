package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

// system-proxmoxのExternalName Service(proxmox-service)はexternalNameに生IPを設定して
// いるため、DNS仕様上CNAMEとして解決できずCoreDNSからNXDOMAINが返る(要修正はこのリポジトリの
// 範囲外)。回避策としてそのIPを直接指定する。
private val proxmoxApiUrl =
    System.getenv("PROXMOX_API_URL") ?: "https://192.168.1.40:8006"
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
data class InfraVmDto(
    val vmid: Int,
    val name: String,
    val status: String,
    val cpuCores: Int?,
    val memoryBytes: Long?,
    val matchedNode: ServerStatusDto? = null
)

@Serializable
data class InfraHostDto(
    val name: String,
    val online: Boolean,
    val cpuCores: Int? = null,
    val memoryBytes: Long? = null,
    val vms: List<InfraVmDto> = emptyList()
)

@Serializable
data class InfrastructureTopologyDto(
    val proxmoxConfigured: Boolean,
    /** Proxmox APIのトークンは設定されているが、呼び出しが失敗した(到達不能・認証エラー等)場合false。
     * hosts/standaloneNodesが空のときにこれで区別しないと、クライアント側で「本当に0台」なのか
     * 「取得に失敗した」のか判別できず、何も表示されない画面になってしまう。 */
    val proxmoxReachable: Boolean = true,
    val hosts: List<InfraHostDto> = emptyList(),
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

/** Proxmoxクラスタの物理ホスト一覧とそこで動くVMを取得し、K8sノード一覧と突き合わせて返す。 */
suspend fun fetchInfrastructureTopology(): InfrastructureTopologyDto {
    val auth = authHeader() ?: return InfrastructureTopologyDto(proxmoxConfigured = false)

    val client = buildProxmoxHttpClient()
    try {
        val nodes = try {
            client.get("$proxmoxApiUrl/api2/json/nodes") {
                header("Authorization", auth)
            }.body<ProxmoxEnvelope<List<ProxmoxNodeDto>>>().data
        } catch (e: Exception) {
            return InfrastructureTopologyDto(proxmoxConfigured = true, proxmoxReachable = false, hosts = emptyList())
        }

        val k8sNodesByName = fetchServerStatuses()?.servers?.associateBy { it.name } ?: emptyMap()
        val matchedNodeNames = mutableSetOf<String>()

        val hosts = nodes.sortedBy { it.node }.map { node ->
            val vms = if (node.status == "online") {
                try {
                    client.get("$proxmoxApiUrl/api2/json/nodes/${node.node}/qemu") {
                        header("Authorization", auth)
                    }.body<ProxmoxEnvelope<List<ProxmoxVmDto>>>().data
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val infraVms = vms.filter { it.status == "running" }.sortedBy { it.name ?: it.vmid.toString() }.map { vm ->
                val matched = vm.name?.let { k8sNodesByName[it] }
                if (matched != null) matchedNodeNames += matched.name
                InfraVmDto(
                    vmid = vm.vmid,
                    name = vm.name ?: "vm-${vm.vmid}",
                    status = vm.status,
                    cpuCores = vm.cpus,
                    memoryBytes = vm.maxmem,
                    matchedNode = matched
                )
            }

            InfraHostDto(
                name = node.node,
                online = node.status == "online",
                cpuCores = node.maxcpu,
                memoryBytes = node.maxmem,
                vms = infraVms
            )
        }

        val standaloneNodes = k8sNodesByName.values.filter { it.name !in matchedNodeNames }.sortedBy { it.name }

        return InfrastructureTopologyDto(proxmoxConfigured = true, hosts = hosts, standaloneNodes = standaloneNodes)
    } finally {
        client.close()
    }
}
