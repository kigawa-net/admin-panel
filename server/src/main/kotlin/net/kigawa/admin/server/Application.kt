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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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

/**
 * 管理用realm(全機能)とpublic用realm(閲覧専用)の2つを独立に検証する。どちらのrealmで
 * 発行されたトークンかはuserinfoエンドポイントへの到達可否で判定する(JWT自体の検証はKeycloak
 * 側のuserinfo呼び出しに委譲している)。public用realmは実運用ではKeycloak側で別途作成が必要。
 */
private val adminRealmUserInfoUrl = System.getenv("KEYCLOAK_ADMIN_USERINFO_URL")
    ?: "https://user.kigawa.net/realms/manage/protocol/openid-connect/userinfo"

private val publicRealmUserInfoUrl = System.getenv("KEYCLOAK_PUBLIC_USERINFO_URL")
    ?: "https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/userinfo"

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
            if (token.isNullOrBlank() || !isValidAnyToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            val rangeMinutes = call.request.queryParameters["rangeMinutes"]?.toIntOrNull()?.coerceIn(5, 1440) ?: 60
            call.respond(queryTraffic(httpClient, rangeMinutes))
        }

        get("/api/network-topology") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAnyToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            call.respond(loadNetworkTopology(httpClient))
        }

        get("/api/servers") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
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

        get("/api/servers/{name}/pods") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }

            val nodeName = call.parameters["name"]
            if (nodeName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                return@get
            }

            val pods = listPodsOnNode(nodeName)
            if (pods == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "pod list unavailable"))
            } else {
                call.respond(pods)
            }
        }

        // 以下は書き込み系のクラスタ操作(Cordon/Uncordon/Drain/Pod削除)。クライアント側で
        // 確認ダイアログを経由してから呼ばれる想定。
        post("/api/servers/{name}/cordon") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val nodeName = call.parameters["name"]
            if (nodeName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                return@post
            }
            call.respond(setNodeSchedulable(nodeName, schedulable = false))
        }

        post("/api/servers/{name}/uncordon") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val nodeName = call.parameters["name"]
            if (nodeName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                return@post
            }
            call.respond(setNodeSchedulable(nodeName, schedulable = true))
        }

        post("/api/servers/{name}/drain") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val nodeName = call.parameters["name"]
            if (nodeName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                return@post
            }
            call.respond(drainNode(nodeName))
        }

        delete("/api/pods/{namespace}/{name}") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@delete
            }
            val namespace = call.parameters["namespace"]
            val name = call.parameters["name"]
            if (namespace.isNullOrBlank() || name.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing namespace or name"))
                return@delete
            }
            call.respond(deletePod(namespace, name))
        }

        // ユーザー管理(manage realmのみ)。Keycloak Admin REST APIを専用サービスアカウント
        // (client_credentials)経由で呼ぶ。サービスアカウント未設定の場合は503を返す。
        get("/api/users") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }
            val users = listKeycloakUsers(httpClient)
            if (users == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "user list unavailable"))
            } else {
                call.respond(users)
            }
        }

        post("/api/users") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val request = try {
                call.receive<CreateUserRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                return@post
            }
            call.respond(createKeycloakUser(httpClient, request))
        }

        delete("/api/users/{id}") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@delete
            }
            val userId = call.parameters["id"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                return@delete
            }
            call.respond(deleteKeycloakUser(httpClient, userId))
        }

        post("/api/users/{id}/enable") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val userId = call.parameters["id"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                return@post
            }
            call.respond(setKeycloakUserEnabled(httpClient, userId, enabled = true))
        }

        post("/api/users/{id}/disable") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val userId = call.parameters["id"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                return@post
            }
            call.respond(setKeycloakUserEnabled(httpClient, userId, enabled = false))
        }

        post("/api/users/{id}/reset-password") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val userId = call.parameters["id"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                return@post
            }
            val request = try {
                call.receive<ResetPasswordRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                return@post
            }
            call.respond(resetKeycloakUserPassword(httpClient, userId, request.newPassword, request.temporary))
        }

        // 組織管理(manage realmのログインのみ許可、対象データはkigawa-net realm)。
        // Keycloak Organizations REST APIを専用サービスアカウント(client_credentials)経由で呼ぶ。
        get("/api/organizations") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }
            val organizations = listOrganizations(httpClient)
            if (organizations == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "organization list unavailable"))
            } else {
                call.respond(organizations)
            }
        }

        post("/api/organizations") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val request = try {
                call.receive<CreateOrganizationRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                return@post
            }
            call.respond(createOrganization(httpClient, request))
        }

        delete("/api/organizations/{id}") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@delete
            }
            val orgId = call.parameters["id"]
            if (orgId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                return@delete
            }
            call.respond(deleteOrganization(httpClient, orgId))
        }

        get("/api/organizations/{id}/members") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }
            val orgId = call.parameters["id"]
            if (orgId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                return@get
            }
            val members = listOrganizationMembers(httpClient, orgId)
            if (members == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "member list unavailable"))
            } else {
                call.respond(members)
            }
        }

        post("/api/organizations/{id}/members") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@post
            }
            val orgId = call.parameters["id"]
            if (orgId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                return@post
            }
            val request = try {
                call.receive<AddOrganizationMemberRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                return@post
            }
            call.respond(addOrganizationMember(httpClient, orgId, request.userId))
        }

        delete("/api/organizations/{id}/members/{userId}") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@delete
            }
            val orgId = call.parameters["id"]
            val userId = call.parameters["userId"]
            if (orgId.isNullOrBlank() || userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id or user id"))
                return@delete
            }
            call.respond(removeOrganizationMember(httpClient, orgId, userId))
        }

        get("/api/organizations/users") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank() || !isValidAdminToken(httpClient, token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
                return@get
            }
            val query = call.request.queryParameters["query"]
            if (query.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing query"))
                return@get
            }
            val users = searchKigawaNetUsers(httpClient, query)
            if (users == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "user search unavailable"))
            } else {
                call.respond(users)
            }
        }
    }
}

/** 管理用realmのトークンのみ許可。サーバー管理(閲覧・操作)エンドポイントで使う。 */
private suspend fun isValidAdminToken(client: HttpClient, token: String): Boolean =
    checkUserInfo(client, adminRealmUserInfoUrl, token)

/** 管理用・public用どちらのrealmのトークンでも許可。ダッシュボード系エンドポイントで使う。 */
private suspend fun isValidAnyToken(client: HttpClient, token: String): Boolean =
    checkUserInfo(client, adminRealmUserInfoUrl, token) || checkUserInfo(client, publicRealmUserInfoUrl, token)

private suspend fun checkUserInfo(client: HttpClient, userInfoUrl: String, token: String): Boolean {
    return try {
        val response: HttpResponse = client.get(userInfoUrl) {
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
