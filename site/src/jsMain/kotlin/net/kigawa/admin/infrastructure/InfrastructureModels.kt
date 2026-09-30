package net.kigawa.admin.infrastructure

import com.varabyte.kobweb.compose.ui.graphics.Colors
import kotlinx.serialization.Serializable
import net.kigawa.admin.servers.ServerStatus
import org.jetbrains.compose.web.css.CSSColorValue
import org.jetbrains.compose.web.css.Color

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

/** nodes/{node}/status 由来のハードウェア情報のみ(issue #158の細粒度取得用)。 */
@Serializable
data class InfraHostHwStatus(
    val cpuModel: String? = null,
    val cpuSockets: Int? = null,
    val cpuPhysicalCores: Int? = null,
    val kernelVersion: String? = null,
    val pveVersion: String? = null,
    val rootfsTotalBytes: Long? = null,
    val rootfsUsedBytes: Long? = null
)

/** PCIeスロット1件分(admin-panel#156)。 */
@Serializable
data class PciSlotInfo(
    val designation: String,
    val type: String? = null,
    val width: String? = null,
    val usage: String? = null,
    val free: Boolean = false
)

/** メモリスロット1件分(admin-panel#156)。 */
@Serializable
data class MemorySlotInfo(
    val locator: String,
    val sizeMb: Long? = null,
    val memType: String? = null,
    val speed: String? = null,
    val free: Boolean = false
)

@Serializable
data class DiskBayDisk(
    val name: String,
    val sizeBytes: Long? = null,
    val model: String? = null
)

@Serializable
data class DiskBayInfo(
    val totalBays: Int? = null,
    val populated: List<DiskBayDisk> = emptyList(),
    val freeBays: Int? = null
)

/** ホストの空きスロット調査結果(admin-panel#156)。 */
@Serializable
data class HostSlotInventory(
    val sshReachable: Boolean = true,
    val pciSlots: List<PciSlotInfo> = emptyList(),
    val memorySlots: List<MemorySlotInfo> = emptyList(),
    val diskBays: DiskBayInfo = DiskBayInfo(),
    /** 仮想マシン上と判定された場合は真。物理スロットの概念がないため表示を抑止する。 */
    val virtualized: Boolean = false,
    val systemProduct: String? = null
)

/** マウントポイント別ディスク使用率(admin-panel#148、SSH+df由来)。 */
@Serializable
data class DiskUsage(
    val mountpoint: String,
    val sizeBytes: Long,
    val usedBytes: Long,
    val availBytes: Long,
    val percent: Int
)

/** リソース使用量グラフの1サンプル(issue #132)。 */
@Serializable
data class ResourceUsagePoint(
    val timestampSeconds: Long,
    val value: Double
)

/** 物理ホスト(Proxmoxノード)のグラフ用時系列(issue #132)。 */
@Serializable
data class PhysicalHostUsageSeries(
    val cpuPercent: List<ResourceUsagePoint> = emptyList(),
    val memGiB: List<ResourceUsagePoint> = emptyList(),
    val maxCpuCores: Int? = null,
    val maxMemGiB: Double? = null
)

/** K8sノード1台分のグラフ用時系列(issue #132)。 */
@Serializable
data class K8sNodeUsageSeries(
    val cpuCores: List<ResourceUsagePoint> = emptyList(),
    val memGiB: List<ResourceUsagePoint> = emptyList(),
    val cpuCapacityCores: Int? = null,
    val memCapacityGiB: Double? = null
)

/** /api/infrastructure/resource-usage のレスポンス(issue #132)。 */
@Serializable
data class ResourceUsageResponse(
    val rangeMinutes: Int,
    val physicalHosts: Map<String, PhysicalHostUsageSeries> = emptyMap(),
    val k8sNodes: Map<String, K8sNodeUsageSeries> = emptyMap()
)

/** グルーピングされた1系列分の集約値(issue #147)。 */
@Serializable
data class GroupedSeries(
    val cpuCores: List<ResourceUsagePoint> = emptyList(),
    val memGiB: List<ResourceUsagePoint> = emptyList(),
    val nodeCount: Int,
    val nodeNames: List<String> = emptyList(),
    val cpuCapacityCores: Double? = null,
    val memCapacityGiB: Double? = null
)

/** グルーピングされたリソース使用量レスポンス(issue #147)。 */
@Serializable
data class GroupedResourceUsageResponse(
    val rangeMinutes: Int,
    val byRole: Map<String, GroupedSeries> = emptyMap(),
    val byPciType: Map<String, GroupedSeries> = emptyMap(),
    val byPhysicalHost: Map<String, GroupedSeries> = emptyMap()
)

/**
 * ディスク使用率に応じた表示色(admin-panel#148)。80%以上で既存の注意色、
 * 90%以上で既存の危険色(赤)を使う。通常値は他の使用量表示と同じグレー。
 */
fun usagePercentColor(percent: Int): CSSColorValue = when {
    percent >= 90 -> Color("#E34948")
    percent >= 80 -> Color("#8A6D00")
    else -> Colors.Gray
}

/** バイト数を読みやすいGiB表記に変換する。 */
fun formatBytesAsGiB(bytes: Long?): String {
    if (bytes == null) return "-"
    val gib = bytes / 1024.0 / 1024.0 / 1024.0
    val rounded = kotlin.math.round(gib * 10) / 10.0
    return "$rounded GiB"
}
