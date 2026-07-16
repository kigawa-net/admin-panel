package net.kigawa.admin.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
    val connections: List<NetworkConnectionDto>
)

private val topologyJson = Json { ignoreUnknownKeys = true }

/**
 * 実際の機器名・IPアドレスはこのパブリックリポジトリにはコミットしない。稼働中クラスタの
 * Secret経由で環境変数 NETWORK_TOPOLOGY_JSON にJSONとして注入し、ここで配信する。
 * 未設定・解析失敗時は非センシティブな汎用トポロジーへフォールバックする。
 */
fun loadNetworkTopology(): NetworkTopologyDto {
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
