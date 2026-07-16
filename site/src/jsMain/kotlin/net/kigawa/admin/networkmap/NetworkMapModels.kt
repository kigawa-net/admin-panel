package net.kigawa.admin.networkmap

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType(val label: String) {
    INTERNET("インターネット"),
    GATEWAY("ゲートウェイ"),
    ROUTER("ルーター"),
    CONTROL_PLANE("コントロールプレーン"),
    WORKER("ワーカーノード"),
    PC("パソコン")
}

@Serializable
data class NetworkDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val ipAddress: String,
    val purpose: String,
    val x: Float,
    val y: Float
)

@Serializable
data class NetworkConnection(
    val fromId: String,
    val toId: String
)

@Serializable
data class NetworkTopology(
    val devices: List<NetworkDevice>,
    val connections: List<NetworkConnection> = emptyList()
)

fun fallbackNetworkTopology(): NetworkTopology {
    val internet = NetworkDevice("internet", "インターネット", DeviceType.INTERNET, "-", "外部ネットワークへの接続", 0.5f, 0.12f)
    val router = NetworkDevice("router", "ルーター", DeviceType.ROUTER, "-", "各機器の通信を中継", 0.5f, 0.38f)
    val server = NetworkDevice("server", "サーバー", DeviceType.CONTROL_PLANE, "-", "各種サービスの実行・管理", 0.5f, 0.64f)
    val pc = NetworkDevice("pc", "パソコン", DeviceType.PC, "-", "開発・管理作業用の端末", 0.5f, 0.90f)
    return NetworkTopology(
        devices = listOf(internet, router, server, pc),
        connections = listOf(
            NetworkConnection(internet.id, router.id),
            NetworkConnection(router.id, server.id),
            NetworkConnection(server.id, pc.id)
        )
    )
}
