package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NetworkDeviceDto(
    val id: String,
    val name: String,
    val type: String,
    val ipAddress: String,
    val purpose: String,
    val x: Float,
    val y: Float
)

@Serializable
data class NetworkConnectionDto(
    val fromId: String,
    val toId: String,
    /** 接続のインターフェイス種別(conntrack-exporterのinterfaceラベル)。WireGuardなら"wg0"等。 */
    @SerialName("interface") val `interface`: String? = null
)

@Serializable
data class NetworkTopologyDto(
    val devices: List<NetworkDeviceDto>,
    val connections: List<NetworkConnectionDto> = emptyList()
)

@Serializable
internal data class PrometheusInstantQueryResponse(
    val status: String? = null,
    val data: PrometheusInstantData? = null
)

@Serializable
internal data class PrometheusInstantData(
    val resultType: String? = null,
    val result: List<PrometheusInstantResult> = emptyList()
)

@Serializable
internal data class PrometheusInstantResult(
    val metric: Map<String, String> = emptyMap(),
    val value: List<JsonElement> = emptyList()
)

/**
 * 機器一覧・接続線のどちらもSecretを使わず、稼働中クラスタから直接収集する。
 *
 * - 機器一覧: Podに自動マウントされるServiceAccount経由でKubernetes APIを直接呼び、
 *   実際のノード名・内部IP・役割(コントロールプレーン/ワーカー)をその場で取得する
 *   ([discoverKubernetesNodes])。in-cluster で実行されていない、またはRBAC未設定などで
 *   取得できない場合のみ、非センシティブな汎用トポロジーへフォールバックする。
 * - 接続線: shumoku と同じ考え方で、conntrack-exporter が収集し Prometheus に集約された
 *   実際の通信ペア(conntrack_bytes_per_second{src,dst})から動的に構築する。実際に通信が
 *   観測された機器同士だけを結ぶため、想定していなかったリンクも反映されうる。
 *   Prometheusから取得できない場合のみ、汎用フォールバックの静的connectionsを使う。
 */
/**
 * 指定された組織IDセットに属するノードのみを含むネットワークトポロジを返す。
 * allowedOrgIds が null の場合は全ノードを許可(管理者用)。
 * ノードのラベル `kigawa.net/organization` で組織IDを判定する。
 */
suspend fun loadNetworkTopology(client: HttpClient, allowedOrgIds: Set<String>? = null): NetworkTopologyDto {
    val discoveredDevices = discoverKubernetesNodes(allowedOrgIds)
    val fallback = genericNetworkTopology()
    // 実ノードが検出できた場合も、K8s APIでは取得できない外部ゲートウェイ(ionos)は
    // 常時追加する(issue #159)。conntrackのIPマッチ対象外(ipAddress="-")のため、
    // 既存の接続線ロジックには影響しない。
    val devices = if (discoveredDevices.isEmpty()) {
        fallback.devices
    } else {
        discoveredDevices + ionosGatewayDevice()
    }

    val liveConnections = queryConntrackConnections(client, devices)
    val connections = liveConnections.ifEmpty {
        if (discoveredDevices.isEmpty()) fallback.connections else emptyList()
    }

    return NetworkTopologyDto(devices = devices, connections = connections)
}

/**
 * IONOS回線側ゲートウェイの静的デバイス定義(issue #159)。
 * K8sノードではない外部機器のためKubernetes APIでは検出できず、ここで常時追加する。
 * 実IP等のセンシティブ情報は含めない。
 */
private fun ionosGatewayDevice(): NetworkDeviceDto =
    NetworkDeviceDto("ionos", "ionosゲートウェイ", "GATEWAY", "-", "IONOS回線側ゲートウェイ (WireGuard/FRR/HAProxy)", 0.5f, 0.12f)

private suspend fun queryConntrackConnections(
    client: HttpClient,
    devices: List<NetworkDeviceDto>
): List<NetworkConnectionDto> {
    val ipToDeviceId = devices.filter { it.ipAddress != "-" }.associate { it.ipAddress to it.id }
    if (ipToDeviceId.isEmpty()) return emptyList()

    val url = URLBuilder("$prometheusUrl/api/v1/query").apply {
        parameters.append("query", "conntrack_bytes_per_second")
    }.buildString()

    val samples = try {
        client.get(url).body<PrometheusInstantQueryResponse>().data?.result.orEmpty()
    } catch (e: Exception) {
        return emptyList()
    }

    val seenPairs = mutableSetOf<Set<String>>()
    val connections = mutableListOf<NetworkConnectionDto>()
    for (sample in samples) {
        val src = sample.metric["src"] ?: continue
        val dst = sample.metric["dst"] ?: continue
        val fromId = ipToDeviceId[src] ?: continue
        val toId = ipToDeviceId[dst] ?: continue
        if (fromId == toId) continue

        val pairKey = setOf(fromId, toId)
        if (seenPairs.add(pairKey)) {
            val iface = sample.metric["interface"]  // conntrack-exporter が出力する場合のみ
            connections.add(NetworkConnectionDto(fromId, toId, iface))
        }
    }
    return connections
}

private fun genericNetworkTopology(): NetworkTopologyDto {
    val internet = NetworkDeviceDto("internet", "インターネット", "INTERNET", "-", "外部ネットワークへの接続", 0.5f, 0.12f)
    val router = NetworkDeviceDto("router", "ルーター", "ROUTER", "-", "各機器の通信を中継", 0.5f, 0.38f)
    // ionosゲートウェイ (IONOS回線側のゲートウェイ、aliceとは別経路)
    val ionos = NetworkDeviceDto("ionos", "ionosゲートウェイ", "GATEWAY", "-", "IONOS回線側ゲートウェイ (WireGuard/FRR/HAProxy)", 0.5f, 0.25f)
    val server = NetworkDeviceDto("server", "サーバー", "CONTROL_PLANE", "-", "各種サービスの実行・管理", 0.5f, 0.64f)
    val pc = NetworkDeviceDto("pc", "パソコン", "PC", "-", "開発・管理作業用の端末", 0.5f, 0.90f)
    return NetworkTopologyDto(
        devices = listOf(internet, router, ionos, server, pc),
        connections = listOf(
            NetworkConnectionDto(internet.id, router.id),
            NetworkConnectionDto(internet.id, ionos.id),
            NetworkConnectionDto(router.id, server.id),
            NetworkConnectionDto(ionos.id, server.id),
            NetworkConnectionDto(server.id, pc.id)
        )
    )
}
