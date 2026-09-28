package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PrometheusMetrics")

// prometheusUrlと、PrometheusInstantQueryResponse等のDTOはNetworkTopology.ktで定義済み
// (接続線取得でも使われている同じクラスタ内Prometheus)。node-exporterは導入されていない
// ため、ノード単位のCPU/メモリ実使用量はnode_cpu_seconds_total等では取得できない。代わりに
// kubelet(cAdvisor)がスクレイプしているコンテナ単位のメトリクスをノードごとに集計して代用する。

data class NodeResourceUsage(val cpuUsageCores: Double?, val memoryUsageBytes: Long?)

/**
 * K8sノードごとのグラフ用時系列(issue #132)。cpuCores/memGiBはそれぞれ
 * (UNIX秒タイムスタンプ, 値)の昇順リスト。
 */
data class NodeResourceUsageSeries(
    val cpuCores: List<Pair<Long, Double>> = emptyList(),
    val memGiB: List<Pair<Long, Double>> = emptyList()
)

@Serializable
private data class PrometheusRangeResponse(
    val status: String? = null,
    val data: PrometheusRangeData? = null
)

@Serializable
private data class PrometheusRangeData(
    val resultType: String? = null,
    val result: List<PrometheusRangeResult> = emptyList()
)

@Serializable
private data class PrometheusRangeResult(
    val metric: Map<String, String> = emptyMap(),
    val values: List<JsonElement> = emptyList()
)

private fun buildPrometheusHttpClient(): HttpClient = HttpClient(CIO) {
    // 以前はこのクライアントにContentNegotiationが無く、body<PrometheusInstantQueryResponse>()
    // が常にNoTransformationFoundExceptionで失敗していた(issue #132の調査で発覚)。
    // 既存の表示は「取得失敗→容量のみ」へフォールバックしていたため気づかれなかったが、
    // fetchNodeResourceUsage()は常に空を返していた。グラフ用のquery_rangeでも同じクライアントを
    // 使うため、ここで必ずJSONデシリアライズできるようにしておく。
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
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

/**
 * ノードごとのCPU/メモリ使用量の時系列をquery_rangeで取得する(issue #132)。
 * グラフ表示はPrometheus自体の障害時に失敗させないため、片方のクエリが失敗した場合は
 * そのシリーズのみ空にする。
 */
suspend fun fetchNodeResourceUsageSeries(rangeMinutes: Int): Map<String, NodeResourceUsageSeries> {
    val client = buildPrometheusHttpClient()
    try {
        val endSeconds = System.currentTimeMillis() / 1000
        val startSeconds = endSeconds - rangeMinutes * 60L
        // トラフィック時系列と同じく、最大120点程度に抑える(クライアント側の描画を軽く保つ)。
        val step = (rangeMinutes * 60 / 120).coerceAtLeast(15)

        val (cpuByNode, memByNode) = coroutineScope {
            val cpuDeferred = async {
                queryRangeNodeMap(
                    client,
                    "sum by (node) (rate(container_cpu_usage_seconds_total{container!=\"\",container!=\"POD\"}[5m]))",
                    startSeconds, endSeconds, step
                )
            }
            val memDeferred = async {
                queryRangeNodeMap(
                    client,
                    "sum by (node) (container_memory_working_set_bytes{container!=\"\",container!=\"POD\"})",
                    startSeconds, endSeconds, step
                )
            }
            cpuDeferred.await() to memDeferred.await()
        }

        val nodeNames = cpuByNode.keys + memByNode.keys
        return nodeNames.associateWith { node ->
            NodeResourceUsageSeries(
                cpuCores = cpuByNode[node].orEmpty(),
                memGiB = memByNode[node].orEmpty().map { (t, v) -> t to v / 1073741824.0 }
            )
        }
    } finally {
        client.close()
    }
}

/** 複数シリーズ対応のquery_range。返り値はメトリクスラベル(ここではnode)ごとの時系列。 */
private suspend fun queryRangeNodeMap(
    client: HttpClient,
    query: String,
    start: Long,
    end: Long,
    step: Int
): Map<String, List<Pair<Long, Double>>> {
    val url = URLBuilder("$prometheusUrl/api/v1/query_range").apply {
        parameters.append("query", query)
        parameters.append("start", start.toString())
        parameters.append("end", end.toString())
        parameters.append("step", "${step}s")
    }.buildString()

    return try {
        val response = client.get(url).body<PrometheusRangeResponse>()
        if (response.status != "success") {
            logger.warn("Prometheus query_range returned non-success status: $query")
            return emptyMap()
        }
        response.data?.result.orEmpty().mapNotNull { result ->
            val node = result.metric["node"] ?: return@mapNotNull null
            val values = result.values.mapNotNull { element ->
                val pair = element.jsonArray
                val timestamp = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toLong() ?: return@mapNotNull null
                val value = pair.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                timestamp to value
            }
            node to values
        }.toMap()
    } catch (e: Exception) {
        logger.warn("Prometheus query_range failed: ${e::class.qualifiedName}: ${e.message}")
        emptyMap()
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
