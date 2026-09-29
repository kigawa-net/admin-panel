package net.kigawa.admin.server

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

@Serializable
data class ServerStatusDto(
    val id: String,
    val name: String,
    val role: String,
    val ready: Boolean,
    val schedulable: Boolean,
    val kubeletVersion: String,
    val osImage: String,
    val cpuCapacity: String,
    val memoryCapacity: String,
    val podCount: Int?,
    val podCapacity: Int?,
    /** クラスタ内のPrometheus(kube-prometheus-stack)から取得した実際のCPU使用コア数。
     * node-exporterが未導入のため、kubelet(cAdvisor)のコンテナ単位メトリクスをノード
     * ごとに集計した近似値。Prometheusに到達できない場合はnull。 */
    val cpuUsageCores: Double? = null,
    /** 実際のメモリ使用量(バイト)。cpuUsageCoresと同様の方法・同様の制約。 */
    val memoryUsageBytes: Long? = null,
    /** NFD(node-feature-discovery)から取得したPCIデバイス情報。
     * ラベル `feature.node.kubernetes.io/pci-<class>_<vendor>.present` 等から抽出。 */
    val pciDevices: List<PciDeviceInfo> = emptyList()
)

@Serializable
data class PciDeviceInfo(
    /** PCIクラス名(例: "3D controller", "Network controller", "Non-Volatile memory controller") */
    val className: String,
    /** PCIベンダーID (16進4桁、例: "10de"=NVIDIA, "8086"=Intel) */
    val vendorId: String,
    /** PCIデバイスID (16進4桁) */
    val deviceId: String,
    /** "true" 固定(NFDの present ラベル由来) */
    val present: Boolean = true
)

@Serializable
data class ServerStatusListDto(val servers: List<ServerStatusDto>)

/** PCIクラスコード(16進)から人間可読な名前へのマッピング(主要なもののみ)。 */
private val PCI_CLASS_NAMES = mapOf(
    "0100" to "SCSI storage controller",
    "0101" to "IDE controller",
    "0102" to "Floppy disk controller",
    "0103" to "IPI bus controller",
    "0104" to "RAID controller",
    "0105" to "ATA controller",
    "0106" to "SATA controller",
    "0107" to "Serial Attached SCSI controller",
    "0180" to "Other storage controller",
    "0200" to "Ethernet controller",
    "0201" to "Token Ring controller",
    "0202" to "FDDI controller",
    "0203" to "ATM controller",
    "0204" to "ISDN controller",
    "0205" to "WorldFip controller",
    "0206" to "PICMG controller",
    "0207" to "Infiniband controller",
    "0280" to "Other network controller",
    "0300" to "VGA compatible controller",
    "0301" to "XGA controller",
    "0302" to "3D controller",
    "0380" to "Other display controller",
    "0400" to "Multimedia video controller",
    "0401" to "Multimedia audio controller",
    "0402" to "Computer telephony device",
    "0403" to "Audio device",
    "0480" to "Other multimedia controller",
    "0500" to "RAM memory",
    "0501" to "Flash memory",
    "0580" to "Other memory controller",
    "0600" to "Host bridge",
    "0601" to "ISA bridge",
    "0602" to "EISA bridge",
    "0603" to "MicroChannel bridge",
    "0604" to "PCI bridge",
    "0605" to "PCMCIA bridge",
    "0605" to "Nubus bridge",
    "0606" to "CardBus bridge",
    "0607" to "RACEway bridge",
    "0608" to "PCI/PCI-X bridge",
    "0680" to "Other bridge",
    "0700" to "Serial controller",
    "0701" to "Parallel controller",
    "0702" to "Multiport serial controller",
    "0703" to "Keyboard controller",
    "0704" to "Floppy disk controller",
    "0705" to "IRDA controller",
    "0780" to "Other communication controller",
    "0800" to "Generic system peripheral",
    "0801" to "Timer",
    "0802" to "RTC",
    "0803" to "Hot-plug controller",
    "0804" to "SD Host controller",
    "0805" to "IOMMU",
    "0880" to "Other system peripheral",
    "0900" to "Keyboard controller",
    "0901" to "Pen digitizer",
    "0902" to "Mouse controller",
    "0903" to "Scanner controller",
    "0904" to "Gameport controller",
    "0980" to "Other input controller",
    "0a00" to "Docking station",
    "0a80" to "Other docking station",
    "0b00" to "Processor",
    "0b01" to "Co-processor",
    "0b80" to "Other processor",
    "0c00" to "FireWire (IEEE 1394) controller",
    "0c01" to "ACCESS.bus controller",
    "0c02" to "SSA controller",
    "0c03" to "USB controller",
    "0c04" to "Fibre Channel controller",
    "0c05" to "SMBus controller",
    "0c06" to "InfiniBand controller",
    "0c07" to "IPMI controller",
    "0c08" to "SERCOS controller",
    "0c09" to "CAN bus controller",
    "0c80" to "Other serial bus controller",
    "0d00" to "Wireless controller",
    "0d01" to "IRDA controller",
    "0d02" to "Consumer IR controller",
    "0d03" to "RF controller",
    "0d04" to "Bluetooth controller",
    "0d05" to "Broadband controller",
    "0d06" to "Ethernet controller (802.11)",
    "0d07" to "Ethernet controller (802.16)",
    "0d80" to "Other wireless controller",
    "0e00" to "Intelligent controller",
    "0e80" to "Other intelligent controller",
    "0f00" to "Satellite controller",
    "0f01" to "TV controller",
    "0f02" to "Audio controller",
    "0f03" to "Voice controller",
    "0f04" to "Data controller",
    "0f80" to "Other satellite/communication controller",
    "1000" to "Encrypt/Decrypt controller",
    "1010" to "Data acquisition and signal processing controller",
    "1080" to "Other data acquisition controller",
    "1100" to "DPIO module",
    "1101" to "Performance counters",
    "1102" to "Communication synchronization",
    "1103" to "Signal processing management",
    "1104" to "Signal generation",
    "1180" to "Other controller",
)

/**
 * NFDのPCIラベルからデバイス情報を抽出する。
 * ラベル形式: `feature.node.kubernetes.io/pci-<class>_<vendor>.present` または
 * `feature.node.kubernetes.io/pci-<vendor>.present`
 */
private fun extractPciDevicesFromLabels(labels: Map<String, String>): List<PciDeviceInfo> {
    val devices = mutableListOf<PciDeviceInfo>()
    for ((key, value) in labels) {
        if (!key.startsWith("feature.node.kubernetes.io/pci-") || !key.endsWith(".present") || value != "true") {
            continue
        }
        // pci-<rest>.present -> <rest> を取得
        val inner = key.substring("feature.node.kubernetes.io/pci-".length, key.length - ".present".length)
        // 形式: class_vendor (例: 0300_10de) または vendor (例: 10de)
        val parts = inner.split("_")
        if (parts.size == 2) {
            val classCode = parts[0].uppercase()
            val vendorId = parts[1].lowercase()
            val className = PCI_CLASS_NAMES[classCode] ?: "Class 0x$classCode"
            devices += PciDeviceInfo(className = className, vendorId = vendorId, deviceId = "unknown")
        } else if (parts.size == 1) {
            val vendorId = parts[0].lowercase()
            devices += PciDeviceInfo(className = "Unknown class", vendorId = vendorId, deviceId = "unknown")
        }
    }
    return devices.distinctBy { it.vendorId }
}

/**
 * 閲覧専用のノード状態一覧。discoverKubernetesNodes と同じくSecret不要、ServiceAccountの
 * nodes(get/list)権限のみで動作する。Pod数の取得にはさらにpods(get/list)権限を使う。
 * ノード一覧とPod数はいずれも取得できなければ(in-cluster以外・RBAC未反映など)nullを返し、
 * 呼び出し側で「取得できません」を表示させる。
 */
suspend fun fetchServerStatuses(): ServerStatusListDto? {
    val apiServerUrl = inClusterApiServerUrl() ?: return null
    val token = readServiceAccountToken() ?: return null
    val client = buildKubernetesHttpClient() ?: return null

    return try {
        val nodeList = client.get("$apiServerUrl/api/v1/nodes") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<K8sNodeList>()

        val podCountByNode: Map<String, Int> = try {
            client.get("$apiServerUrl/api/v1/pods") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.body<K8sPodList>().items
                .mapNotNull { it.spec.nodeName }
                .groupingBy { it }
                .eachCount()
        } catch (e: Exception) {
            emptyMap()
        }

        val resourceUsageByNode = try {
            fetchNodeResourceUsage()
        } catch (e: Exception) {
            emptyMap()
        }

        val servers = nodeList.items.map { node ->
            val ready = node.status.conditions.firstOrNull { it.type == "Ready" }?.status == "True"
            val usage = resourceUsageByNode[node.metadata.name]
            val pciDevices = extractPciDevicesFromLabels(node.metadata.labels)
            ServerStatusDto(
                id = node.metadata.name,
                name = node.metadata.name,
                role = if (node.isControlPlane()) "CONTROL_PLANE" else "WORKER",
                ready = ready,
                schedulable = !node.spec.unschedulable,
                kubeletVersion = node.status.nodeInfo.kubeletVersion,
                osImage = node.status.nodeInfo.osImage,
                cpuCapacity = node.status.capacity["cpu"] ?: "-",
                memoryCapacity = node.status.capacity["memory"] ?: "-",
                podCount = podCountByNode[node.metadata.name],
                podCapacity = node.status.capacity["pods"]?.toIntOrNull(),
                cpuUsageCores = usage?.cpuUsageCores,
                memoryUsageBytes = usage?.memoryUsageBytes,
                pciDevices = pciDevices
            )
        }
        ServerStatusListDto(servers)
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}
