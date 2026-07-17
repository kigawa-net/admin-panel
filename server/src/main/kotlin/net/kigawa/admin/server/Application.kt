package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Internal cluster DNS for the kube-prometheus-stack Prometheus service (see kigawa01/k8s-system). */
internal val prometheusUrl =
    System.getenv("PROMETHEUS_URL") ?: "http://prometheus-operated.prometheus.svc.cluster.local:9090"

private val keycloakUserInfoUrl = System.getenv("KEYCLOAK_USERINFO_URL")
    ?: "https://user.kigawa.net/realms/manage/protocol/openid-connect/userinfo"

@Serializable
data class TrafficPoint(
    @SerialName("timestampSeconds") val timestampSeconds: Long,
    @SerialName("rxBitsPerSecond") val rxBitsPerSecond: Double,
    @SerialName("txBitsPerSecond") val txBitsPerSecond: Double
)

@Serializable
data class TrafficResponse(
    @SerialName("rangeMinutes") val rangeMinutes: Int,
    @SerialName("series") val series: List<TrafficPoint>
)

@Serializable
private data class PrometheusQueryRangeResponse(
    val status: String? = null,
    val data: PrometheusData? = null
)

@Serializable
private data class PrometheusData(
    val resultType: String? = null,
    val result: List<PrometheusResult> = emptyList()
)

@Serializable
private data class PrometheusResult(
    val metric: Map<String, String> = emptyMap(),
    val values: List<JsonElement> = emptyList()
)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/api/traffic") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            val rangeMinutes = call.request.queryParameters["rangeMinutes"]?.toIntOrNull()?.coerceIn(5, 1440) ?: 60
            call.respond(queryTraffic(httpClient, rangeMinutes))
        }

        get("/api/network-topology") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            call.respond(loadNetworkTopology(httpClient))
        }

        get("/api/servers") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            val statuses = fetchServerStatuses()
            if (statuses == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "server status unavailable"))
            } else {
                call.respond(statuses)
            }
        }
    }
}

private suspend fun isValidToken(client: HttpClient, token: String): Boolean {
    return try {
        val response: HttpResponse = client.get(keycloakUserInfoUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        false
    }
}

private suspend fun queryTraffic(client: HttpClient, rangeMinutes: Int): TrafficResponse {
    val endSeconds = System.currentTimeMillis() / 1000
    val startSeconds = endSeconds - rangeMinutes * 60L
    // Aim for roughly one data point per step, capped to keep the response small.
    val step = (rangeMinutes * 60 / 120).coerceAtLeast(15)

    val rx = queryRange(
        client,
        query = "sum(rate(node_network_receive_bytes_total{device=\"wg0\"}[5m])) * 8",
        start = startSeconds,
        end = endSeconds,
        step = step
    )
    val tx = queryRange(
        client,
        query = "sum(rate(node_network_transmit_bytes_total{device=\"wg0\"}[5m])) * 8",
        start = startSeconds,
        end = endSeconds,
        step = step
    )

    val rxByTime = rx.toMap()
    val txByTime = tx.toMap()
    val timestamps = (rxByTime.keys + txByTime.keys).toSortedSet()

    val series = timestamps.map { timestamp ->
        TrafficPoint(
            timestampSeconds = timestamp,
            rxBitsPerSecond = rxByTime[timestamp] ?: 0.0,
            txBitsPerSecond = txByTime[timestamp] ?: 0.0
        )
    }

    return TrafficResponse(rangeMinutes = rangeMinutes, series = series)
}

private suspend fun queryRange(
    client: HttpClient,
    query: String,
    start: Long,
    end: Long,
    step: Int
): List<Pair<Long, Double>> {
    val url = URLBuilder("$prometheusUrl/api/v1/query_range").apply {
        parameters.append("query", query)
        parameters.append("start", start.toString())
        parameters.append("end", end.toString())
        parameters.append("step", "${step}s")
    }.buildString()

    val response = client.get(url).body<PrometheusQueryRangeResponse>()
    val firstSeries = response.data?.result?.firstOrNull() ?: return emptyList()

    return firstSeries.values.mapNotNull { element ->
        val pair = element.jsonArray
        val timestamp = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toLong() ?: return@mapNotNull null
        val value = pair.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        timestamp to value
    }
}
