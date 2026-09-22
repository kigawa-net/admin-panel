package net.kigawa.admin.infrastructure

import kotlinx.serialization.Serializable
import net.kigawa.admin.servers.ServerStatus

@Serializable
data class InfraVm(
    val vmid: Int,
    val name: String,
    val status: String,
    val cpuCores: Int? = null,
    val memoryBytes: Long? = null,
    val matchedNode: ServerStatus? = null
)

@Serializable
data class InfraDisk(
    val devpath: String,
    val model: String,
    val type: String,
    val sizeBytes: Long? = null,
    val health: String? = null
)

@Serializable
data class InfraPciDevice(
    val name: String,
    val vendor: String? = null
)

/** ホストの基本情報のみ(高速パス、/api/infrastructure、/nodes呼び出し1回で完結)。
 * ノードごとに追加呼び出しが必要なハードウェア詳細・VM/ディスク/PCIはいずれも
 * InfraHostDetails(/api/infrastructure/details)側で非同期に読み込む(issue #118)。 */
@Serializable
data class InfraHost(
    val name: String,
    val online: Boolean,
    val cpuCores: Int? = null,
    val memoryBytes: Long? = null
)

@Serializable
data class InfrastructureTopology(
    val proxmoxConfigured: Boolean,
    val proxmoxReachable: Boolean = true,
    val hosts: List<InfraHost> = emptyList()
)

@Serializable
data class InfraHostDetails(
    val disks: List<InfraDisk> = emptyList(),
    val pciDevices: List<InfraPciDevice> = emptyList(),
    val vms: List<InfraVm> = emptyList(),
    val cpuModel: String? = null,
    val cpuSockets: Int? = null,
    val cpuPhysicalCores: Int? = null,
    val kernelVersion: String? = null,
    val pveVersion: String? = null,
    val rootfsTotalBytes: Long? = null,
    val rootfsUsedBytes: Long? = null
)

@Serializable
data class InfrastructureDetails(
    val hostDetails: Map<String, InfraHostDetails> = emptyMap(),
    val standaloneNodes: List<ServerStatus> = emptyList()
)

/** バイト数を読みやすいGiB表記に変換する。 */
fun formatBytesAsGiB(bytes: Long?): String {
    if (bytes == null) return "-"
    val gib = bytes / 1024.0 / 1024.0 / 1024.0
    val rounded = kotlin.math.round(gib * 10) / 10.0
    return "$rounded GiB"
}
