package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

private val topologyJson = Json { ignoreUnknownKeys = true }

/**
 * 機器の識別情報(名前・種別・IP・レイアウト座標)は実データを含むため、このパブリック
 * リポジトリにはコミットしない。稼働中クラスタのSecret経由で環境変数 NETWORK_TOPOLOGY_JSON
 * にJSONとして注入し、ここで配信する。未設定・解析失敗時は非センシティブな汎用トポロジーへ
 * フォールバックする。
 *
 * 接続線(connections)は shumoku と同じ考え方で、conntrack-exporter が収集し Prometheus に
 * 集約された実際の通信ペア(conntrack_bytes_per_second{src,dst})から動的に構築する。実際に
 * 通信が観測された機器同士だけを結ぶため、静的に書いたことがない・想定していないリンクも
 * 反映される。Prometheusから取得できない場合のみ、Secret側に static に書かれた connections
 * (それも無ければ汎用フォールバック)を使う。
 */
suspend fun loadNetworkTopology(client: HttpClient): NetworkTopologyDto {
    val staticTopology = loadStaticTopology()
    val liveConnections = queryConntrackConnections(client, staticTopology.devices)

    return staticTopology.copy(
        connections = liveConnections.ifEmpty { staticTopology.connections }
    )
}

private fun loadStaticTopology(): NetworkTopologyDto {
    val raw = System.getenv("NETWORK_TOPOLOGY_JSON")
    if (raw != null) {
        try {
            return topologyJson.decodeFromString<NetworkTopologyDto>(raw)
        } catch (e: Exception) {
            // fall through to the generic topology below
        }
    }
    return genericNetworkTopology()
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
