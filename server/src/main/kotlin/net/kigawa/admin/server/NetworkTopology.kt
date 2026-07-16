package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
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
    val toId: String
)

@Serializable
data class NetworkTopologyDto(
    val devices: List<NetworkDeviceDto>,
    val connections: List<NetworkConnectionDto> = emptyList()
)

@Serializable
private data class PrometheusInstantQueryResponse(
    val status: String? = null,
    val data: PrometheusInstantData? = null
)

@Serializable
private data class PrometheusInstantData(
    val resultType: String? = null,
    val result: List<PrometheusInstantResult> = emptyList()
)

@Serializable
private data class PrometheusInstantResult(
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
suspend fun loadNetworkTopology(client: HttpClient): NetworkTopologyDto {
    val discoveredDevices = discoverKubernetesNodes()
    val fallback = genericNetworkTopology()
    val devices = discoveredDevices.ifEmpty { fallback.devices }

    val liveConnections = queryConntrackConnections(client, devices)
    val connections = liveConnections.ifEmpty {
        if (discoveredDevices.isEmpty()) fallback.connections else emptyList()
    }

    return NetworkTopologyDto(devices = devices, connections = connections)
}

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
            connections.add(NetworkConnectionDto(fromId, toId))
        }
    }
    return connections
}

private fun genericNetworkTopology(): NetworkTopologyDto {
    val internet = NetworkDeviceDto("internet", "インターネット", "INTERNET", "-", "外部ネットワークへの接続", 0.5f, 0.12f)
    val router = NetworkDeviceDto("router", "ルーター", "ROUTER", "-", "各機器の通信を中継", 0.5f, 0.38f)
    val server = NetworkDeviceDto("server", "サーバー", "CONTROL_PLANE", "-", "各種サービスの実行・管理", 0.5f, 0.64f)
    val pc = NetworkDeviceDto("pc", "パソコン", "PC", "-", "開発・管理作業用の端末", 0.5f, 0.90f)
    return NetworkTopologyDto(
        devices = listOf(internet, router, server, pc),
        connections = listOf(
            NetworkConnectionDto(internet.id, router.id),
            NetworkConnectionDto(router.id, server.id),
            NetworkConnectionDto(server.id, pc.id)
        )
    )
}
