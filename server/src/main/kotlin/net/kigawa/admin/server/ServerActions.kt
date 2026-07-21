package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class PodSummaryDto(
    val namespace: String,
    val name: String,
    val ownerKind: String
)

@Serializable
data class PodListDto(val pods: List<PodSummaryDto>)

@Serializable
data class ActionResultDto(val success: Boolean, val message: String)

@Serializable
data class GracefulShutdownRequestDto(val drainTimeoutSeconds: Int = 60)

@Serializable
data class DrainResultDto(
    val evicted: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String>
)

private const val MERGE_PATCH_CONTENT_TYPE = "application/merge-patch+json"

/**
 * すべて明示的な操作(Cordon/Uncordon/Drain/Pod削除)であり、書き込み系のクラスタ操作。
 * クライアント側で確認ダイアログを経由してから呼ばれる想定。DaemonSet管理下のPodはDrain対象から
 * 除外する(Drain後すぐ同じノードに再スケジュールされ、退避の意味がないため)。
 */
suspend fun setNodeSchedulable(nodeName: String, schedulable: Boolean): ActionResultDto {
    val apiServerUrl = inClusterApiServerUrl() ?: return ActionResultDto(false, "in-cluster でのみ実行できます")
    val token = readServiceAccountToken() ?: return ActionResultDto(false, "ServiceAccountトークンがありません")
    val client = buildKubernetesHttpClient() ?: return ActionResultDto(false, "クラスタへの接続に失敗しました")

    return try {
        val response = client.patch("$apiServerUrl/api/v1/nodes/$nodeName") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.parse(MERGE_PATCH_CONTENT_TYPE))
            setBody("""{"spec":{"unschedulable":${!schedulable}}}""")
        }
        if (response.status == HttpStatusCode.OK) {
            ActionResultDto(true, if (schedulable) "スケジューリングを再開しました" else "スケジューリングを停止しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    } finally {
        client.close()
    }
}

suspend fun listPodsOnNode(nodeName: String): PodListDto? {
    val apiServerUrl = inClusterApiServerUrl() ?: return null
    val token = readServiceAccountToken() ?: return null
    val client = buildKubernetesHttpClient() ?: return null

    return try {
        val podList = client.get("$apiServerUrl/api/v1/pods") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<K8sPodList>()

        val pods = podList.items
            .filter { it.spec.nodeName == nodeName }
            .map {
                PodSummaryDto(
                    namespace = it.metadata.namespace,
                    name = it.metadata.name,
                    ownerKind = it.metadata.ownerReferences.firstOrNull()?.kind ?: "-"
                )
            }
        PodListDto(pods)
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}

suspend fun deletePod(namespace: String, name: String): ActionResultDto {
    val apiServerUrl = inClusterApiServerUrl() ?: return ActionResultDto(false, "in-cluster でのみ実行できます")
    val token = readServiceAccountToken() ?: return ActionResultDto(false, "ServiceAccountトークンがありません")
    val client = buildKubernetesHttpClient() ?: return ActionResultDto(false, "クラスタへの接続に失敗しました")

    return try {
        val response: HttpResponse = client.delete("$apiServerUrl/api/v1/namespaces/$namespace/pods/$name") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status == HttpStatusCode.OK) {
            ActionResultDto(true, "再起動しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    } finally {
        client.close()
    }
}

suspend fun drainNode(nodeName: String): DrainResultDto {
    val cordonResult = setNodeSchedulable(nodeName, schedulable = false)
    if (!cordonResult.success) {
        return DrainResultDto(0, 0, 1, listOf("cordonに失敗しました: ${cordonResult.message}"))
    }

    val apiServerUrl = inClusterApiServerUrl()
        ?: return DrainResultDto(0, 0, 1, listOf("in-cluster でのみ実行できます"))
    val token = readServiceAccountToken()
        ?: return DrainResultDto(0, 0, 1, listOf("ServiceAccountトークンがありません"))
    val client = buildKubernetesHttpClient()
        ?: return DrainResultDto(0, 0, 1, listOf("クラスタへの接続に失敗しました"))

    return try {
        val podList = client.get("$apiServerUrl/api/v1/pods") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<K8sPodList>()

        val podsOnNode = podList.items.filter { it.spec.nodeName == nodeName }
        var evicted = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (pod in podsOnNode) {
            val ownerKind = pod.metadata.ownerReferences.firstOrNull()?.kind
            if (ownerKind == "DaemonSet") {
                skipped++
                continue
            }
            try {
                val evictionUrl = "$apiServerUrl/api/v1/namespaces/${pod.metadata.namespace}/pods/${pod.metadata.name}/eviction"
                val response = client.post(evictionUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"${pod.metadata.name}","namespace":"${pod.metadata.namespace}"}}"""
                    )
                }
                if (response.status.value in 200..299) {
                    evicted++
                } else {
                    failed++
                    errors.add("${pod.metadata.namespace}/${pod.metadata.name}: HTTP ${response.status.value}")
                }
            } catch (e: Exception) {
                failed++
                errors.add("${pod.metadata.namespace}/${pod.metadata.name}: ${e.message ?: "unknown error"}")
            }
        }

        DrainResultDto(evicted, skipped, failed, errors)
    } catch (e: Exception) {
        DrainResultDto(0, 0, 1, listOf(e.message ?: "失敗しました"))
    } finally {
        client.close()
    }
}

/** ノードのInternalIPを取得する。SSH経由での電源操作(NodeSshOperations)の接続先として使う。 */
internal suspend fun getNodeInternalIp(nodeName: String): String? {
    val apiServerUrl = inClusterApiServerUrl() ?: return null
    val token = readServiceAccountToken() ?: return null
    val client = buildKubernetesHttpClient() ?: return null

    return try {
        val node = client.get("$apiServerUrl/api/v1/nodes/$nodeName") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<K8sNode>()
        node.status.addresses.firstOrNull { it.type == "InternalIP" }?.address
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}

/**
 * Cordon → Drain(Eviction発行)→ 対象ノード上の非DaemonSet Podが実際に無くなるまで
 * 最大drainTimeoutSeconds秒だけ待つ → SSH経由で実際にシャットダウン/再起動する。
 * タイムアウトに達した場合もPodの残存有無に関わらず処理を続行する(#50: 無限に待たない)。
 */
suspend fun gracefulNodeShutdown(nodeName: String, drainTimeoutSeconds: Int, reboot: Boolean): ActionResultDto {
    if (!NodeSshOperations.isConfigured) {
        return ActionResultDto(false, "ノードSSHの認証情報が未設定です")
    }

    val hostIp = getNodeInternalIp(nodeName)
        ?: return ActionResultDto(false, "ノードのIPアドレスを取得できませんでした")

    val drainResult = drainNode(nodeName)
    if (drainResult.failed > 0 && drainResult.evicted == 0 && drainResult.skipped == 0) {
        // cordon自体が失敗した場合など、evictionが一件も発行できていない場合は中断する
        return ActionResultDto(false, "Drainに失敗しました: ${drainResult.errors.joinToString()}")
    }

    waitForDrainCompletion(nodeName, drainTimeoutSeconds)

    return if (reboot) NodeSshOperations.reboot(hostIp) else NodeSshOperations.shutdown(hostIp)
}

private const val DRAIN_POLL_INTERVAL_MS = 3_000L

private suspend fun waitForDrainCompletion(nodeName: String, timeoutSeconds: Int) {
    if (timeoutSeconds <= 0) return
    val apiServerUrl = inClusterApiServerUrl() ?: return
    val token = readServiceAccountToken() ?: return
    val client = buildKubernetesHttpClient() ?: return

    try {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val remaining = try {
                client.get("$apiServerUrl/api/v1/pods") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.body<K8sPodList>().items.count {
                    it.spec.nodeName == nodeName && it.metadata.ownerReferences.firstOrNull()?.kind != "DaemonSet"
                }
            } catch (e: Exception) {
                0
            }
            if (remaining == 0) return
            delay(DRAIN_POLL_INTERVAL_MS)
        }
    } finally {
        client.close()
    }
}
