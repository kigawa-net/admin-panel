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
    val memoryUsageBytes: Long? = null
)

@Serializable
data class ServerStatusListDto(val servers: List<ServerStatusDto>)

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
                memoryUsageBytes = usage?.memoryUsageBytes
            )
        }
        ServerStatusListDto(servers)
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}
