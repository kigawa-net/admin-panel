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
    val podCapacity: Int? = null
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

fun formatMemoryCapacity(raw: String): String {
    val kibValue = raw.removeSuffix("Ki").toLongOrNull() ?: return raw
    val gib = kibValue / 1024.0 / 1024.0
    val rounded = (kotlin.math.round(gib * 10) / 10.0)
    return "$rounded GiB"
}
