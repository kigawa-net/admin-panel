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
data class InfraHost(
    val name: String,
    val online: Boolean,
    val cpuCores: Int? = null,
    val memoryBytes: Long? = null,
    val vms: List<InfraVm> = emptyList()
)

@Serializable
data class InfrastructureTopology(
    val proxmoxConfigured: Boolean,
    val hosts: List<InfraHost> = emptyList(),
    val standaloneNodes: List<ServerStatus> = emptyList()
)

/** バイト数を読みやすいGiB表記に変換する。 */
fun formatBytesAsGiB(bytes: Long?): String {
    if (bytes == null) return "-"
    val gib = bytes / 1024.0 / 1024.0 / 1024.0
    val rounded = kotlin.math.round(gib * 10) / 10.0
    return "$rounded GiB"
}
