package net.kigawa.admin.networkmap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType(val label: String) {
    @SerialName("INTERNET") INTERNET("インターネット"),
    @SerialName("GATEWAY") GATEWAY("ゲートウェイ"),
    @SerialName("ROUTER") ROUTER("ルーター"),
    @SerialName("CONTROL_PLANE") CONTROL_PLANE("コントロールプレーン"),
    @SerialName("WORKER") WORKER("ワーカーノード"),
    @SerialName("PC") PC("パソコン")
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
    val connections: List<NetworkConnection>
)

/**
 * 実インフラのトポロジーは admin-panel-api (`/api/network-topology`) が配信する。実IPアドレス
 * や機器名を含む本物の構成をこのパブリックリポジトリにハードコードしないため、ここでは
 * バックエンドへ到達できない場合に表示する汎用フォールバックのみを持つ。
 */
fun fallbackNetworkTopology(): NetworkTopology {
    val internet = NetworkDevice(
        id = "internet",
        name = "インターネット",
        type = DeviceType.INTERNET,
        ipAddress = "-",
        purpose = "外部ネットワークへの接続",
        x = 0.5f,
        y = 0.12f
    )
    val router = NetworkDevice(
        id = "router",
        name = "ルーター",
        type = DeviceType.ROUTER,
        ipAddress = "-",
        purpose = "各機器の通信を中継",
        x = 0.5f,
        y = 0.38f
    )
    val server = NetworkDevice(
        id = "server",
        name = "サーバー",
        type = DeviceType.CONTROL_PLANE,
        ipAddress = "-",
        purpose = "各種サービスの実行・管理",
        x = 0.5f,
        y = 0.64f
    )
    val pc = NetworkDevice(
        id = "pc",
        name = "パソコン",
        type = DeviceType.PC,
        ipAddress = "-",
        purpose = "開発・管理作業用の端末",
        x = 0.5f,
        y = 0.90f
    )

    return NetworkTopology(
        devices = listOf(internet, router, server, pc),
        connections = listOf(
            NetworkConnection(internet.id, router.id),
            NetworkConnection(router.id, server.id),
            NetworkConnection(server.id, pc.id)
        )
    )
}
