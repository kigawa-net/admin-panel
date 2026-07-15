package net.kigawa.admin.networkmap

enum class DeviceType(val label: String) {
    INTERNET("インターネット"),
    ROUTER("ルーター"),
    SERVER("サーバー"),
    PC("パソコン")
}

data class NetworkDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val ipAddress: String,
    val purpose: String,
    val x: Float,
    val y: Float
)

data class NetworkConnection(
    val fromId: String,
    val toId: String
)

data class NetworkTopology(
    val devices: List<NetworkDevice>,
    val connections: List<NetworkConnection>
)

fun kigawaNetTopology(): NetworkTopology {
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
        ipAddress = "192.168.1.1",
        purpose = "各機器の通信を中継",
        x = 0.5f,
        y = 0.38f
    )
    val mainServer = NetworkDevice(
        id = "main-server",
        name = "メインサーバー",
        type = DeviceType.SERVER,
        ipAddress = "192.168.1.10",
        purpose = "各種サービスの実行・管理",
        x = 0.5f,
        y = 0.64f
    )
    val pc = NetworkDevice(
        id = "pc",
        name = "パソコン",
        type = DeviceType.PC,
        ipAddress = "192.168.1.20",
        purpose = "開発・管理作業用の端末",
        x = 0.5f,
        y = 0.9f
    )

    return NetworkTopology(
        devices = listOf(internet, router, mainServer, pc),
        connections = listOf(
            NetworkConnection(internet.id, router.id),
            NetworkConnection(router.id, mainServer.id),
            NetworkConnection(mainServer.id, pc.id)
        )
    )
}
