package net.kigawa.admin.servers

import kotlinx.serialization.Serializable

@Serializable
data class ServerStatus(
    val id: String,
    val name: String,
    val role: String,
    val ready: Boolean,
    val schedulable: Boolean = true,
    val kubeletVersion: String,
    val osImage: String,
    val cpuCapacity: String,
    val memoryCapacity: String,
    val podCount: Int? = null,
    val podCapacity: Int? = null,
    val cpuUsageCores: Double? = null,
    val memoryUsageBytes: Long? = null
)

@Serializable
data class ServerStatusList(val servers: List<ServerStatus>)

@Serializable
data class PodSummary(
    val namespace: String,
    val name: String,
    val ownerKind: String
)

@Serializable
data class PodList(val pods: List<PodSummary>)

@Serializable
data class ActionResult(val success: Boolean, val message: String)

@Serializable
data class GracefulShutdownRequest(val drainTimeoutSeconds: Int = 60)

@Serializable
data class DrainResult(
    val evicted: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

fun roleLabel(role: String): String = when (role) {
    "CONTROL_PLANE" -> "コントロールプレーン"
    "WORKER" -> "ワーカーノード"
    else -> role
}

/** Kubernetesのメモリ容量は "18415404Ki" のような単位付き文字列で返るため、読みやすいGiB表記に変換する。 */
fun formatMemoryCapacity(raw: String): String {
    val kibValue = raw.removeSuffix("Ki").toLongOrNull() ?: return raw
    val gib = kibValue / 1024.0 / 1024.0
    val rounded = (kotlin.math.round(gib * 10) / 10.0)
    return "$rounded GiB"
}

/** Prometheusから実使用量を取得できなかった場合はnullを返す(呼び出し側は表示自体を省略する)。 */
fun formatCpuUsage(usageCores: Double?, capacityCores: String): String? {
    if (usageCores == null) return null
    val capacity = capacityCores.toDoubleOrNull()
    val roundedUsage = kotlin.math.round(usageCores * 10) / 10.0
    return if (capacity != null && capacity > 0) {
        val percent = kotlin.math.round(usageCores / capacity * 100)
        "$roundedUsage / $capacityCores コア (${percent.toInt()}%)"
    } else {
        "$roundedUsage コア"
    }
}

fun formatMemoryUsage(usageBytes: Long?, capacityKi: String): String? {
    if (usageBytes == null) return null
    val usageGib = usageBytes / 1024.0 / 1024.0 / 1024.0
    val roundedUsage = kotlin.math.round(usageGib * 10) / 10.0
    val capacityKib = capacityKi.removeSuffix("Ki").toLongOrNull()
    return if (capacityKib != null && capacityKib > 0) {
        val capacityGib = capacityKib / 1024.0 / 1024.0
        val percent = kotlin.math.round(usageBytes / (capacityKib * 1024.0) * 100)
        val roundedCapacity = kotlin.math.round(capacityGib * 10) / 10.0
        "$roundedUsage / $roundedCapacity GiB (${percent.toInt()}%)"
    } else {
        "$roundedUsage GiB"
    }
}
