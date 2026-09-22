package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PrometheusMetrics")

// prometheusUrlと、PrometheusInstantQueryResponse等のDTOはNetworkTopology.ktで定義済み
// (接続線取得でも使われている同じクラスタ内Prometheus)。node-exporterは導入されていない
// ため、ノード単位のCPU/メモリ実使用量はnode_cpu_seconds_total等では取得できない。代わりに
// kubelet(cAdvisor)がスクレイプしているコンテナ単位のメトリクスをノードごとに集計して代用する。

data class NodeResourceUsage(val cpuUsageCores: Double?, val memoryUsageBytes: Long?)

private fun buildPrometheusHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
    }
}

/**
 * ノードごとの実際のCPU使用コア数・メモリ使用量(バイト)を返す。Prometheus自体に
 * 到達できない、あるいはクエリが失敗した場合は空マップを返し、呼び出し側は既存の
 * capacity(割当容量)のみの表示にフォールバックする(サーバー管理画面全体は失敗させない)。
 */
suspend fun fetchNodeResourceUsage(): Map<String, NodeResourceUsage> {
    val client = buildPrometheusHttpClient()
    try {
        val (cpuByNode, memByNode) = coroutineScope {
            val cpuDeferred = async {
                queryPrometheusVector(
                    client,
                    "sum by (node) (rate(container_cpu_usage_seconds_total{container!=\"\",container!=\"POD\"}[5m]))"
                )
            }
            val memDeferred = async {
                queryPrometheusVector(
                    client,
                    "sum by (node) (container_memory_working_set_bytes{container!=\"\",container!=\"POD\"})"
                )
            }
            cpuDeferred.await() to memDeferred.await()
        }

        val nodeNames = cpuByNode.keys + memByNode.keys
        return nodeNames.associateWith { node ->
            NodeResourceUsage(
                cpuUsageCores = cpuByNode[node],
                memoryUsageBytes = memByNode[node]?.toLong()
            )
        }
    } finally {
        client.close()
    }
}

private suspend fun queryPrometheusVector(client: HttpClient, query: String): Map<String, Double> {
    val url = URLBuilder("$prometheusUrl/api/v1/query").apply {
        parameters.append("query", query)
    }.buildString()

    return try {
        val response = client.get(url).body<PrometheusInstantQueryResponse>()
        if (response.status != "success") {
            logger.warn("Prometheus query returned non-success status: $query")
            return emptyMap()
        }
        response.data?.result.orEmpty().mapNotNull { result ->
            val node = result.metric["node"] ?: return@mapNotNull null
            val value = result.value.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            node to value
        }.toMap()
    } catch (e: Exception) {
        logger.warn("Prometheus query failed: ${e::class.qualifiedName}: ${e.message}")
        emptyMap()
    }
}
