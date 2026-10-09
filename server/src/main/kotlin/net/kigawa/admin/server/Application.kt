package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import net.kigawa.admin.server.mcpJwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.admin.server.mcp.McpRequest
import net.kigawa.admin.server.mcp.McpResponse
import net.kigawa.admin.server.mcp.McpServerInstance

/** Internal cluster DNS for the kube-prometheus-stack Prometheus service (see kigawa01/k8s-system). */
internal val prometheusUrl =
    System.getenv("PROMETHEUS_URL") ?: "http://prometheus-operated.prometheus.svc.cluster.local:9090"


/**
 * Expected `aud` claim on GitHub Actions OIDC tokens presented to the CI-facing GitHub App
 * token endpoint (custom action -> admin-panel -> App). Callers request an ID token scoped to
 * this audience via `core.getIDToken(audience)`; no shared secret is involved.
 */
private val adminPanelActionsAudience =
    System.getenv("ADMIN_PANEL_ACTIONS_AUDIENCE") ?: "https://admin.kigawa.net"

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

/**
 * インフラのリソース利用量グラフ用レスポンス(issue #132)。
 * - physicalHosts: Proxmox rrddata による物理ホスト実使用量(CPU %, メモリ GiB)
 * - k8sNodes: Prometheus(cAdvisor) によるK8sノードのコンテナ使用量(CPUコア, メモリ GiB)
 */
@Serializable
data class InfrastructureResourceUsageResponse(
    @SerialName("rangeMinutes") val rangeMinutes: Int,
    @SerialName("physicalHosts") val physicalHosts: Map<String, PhysicalHostUsageDto> = emptyMap(),
    @SerialName("k8sNodes") val k8sNodes: Map<String, K8sNodeUsageDto> = emptyMap()
)

/** K8sノード1台分のグラフ用時系列(issue #132)。 */
@Serializable
data class K8sNodeUsageDto(
    @SerialName("cpuCores") val cpuCores: List<ResourceUsagePointDto> = emptyList(),
    @SerialName("memGiB") val memGiB: List<ResourceUsagePointDto> = emptyList(),
    @SerialName("cpuCapacityCores") val cpuCapacityCores: Int? = null,
    @SerialName("memCapacityGiB") val memCapacityGiB: Double? = null
)

/**
 * グルーピングされたリソース使用量(issue #147)。
 * 複数のグルーピング軸(role/pciType/physicalHost)を一括返却し、
 * フロント側でタブ切り替えできるようにする。
 */
@Serializable
data class GroupedResourceUsageResponse(
    @SerialName("rangeMinutes") val rangeMinutes: Int,
    @SerialName("byRole") val byRole: Map<String, GroupedSeries> = emptyMap(),
    @SerialName("byPciType") val byPciType: Map<String, GroupedSeries> = emptyMap(),
    @SerialName("byPhysicalHost") val byPhysicalHost: Map<String, GroupedSeries> = emptyMap()
)

/** グループ化された1系列分の集約値(issue #147)。 */
@Serializable
data class GroupedSeries(
    @SerialName("cpuCores") val cpuCores: List<ResourceUsagePointDto> = emptyList(),
    @SerialName("memGiB") val memGiB: List<ResourceUsagePointDto> = emptyList(),
    @SerialName("nodeCount") val nodeCount: Int,
    @SerialName("nodeNames") val nodeNames: List<String> = emptyList(),
    /** グループ内ノードのCPU容量合計(コア)。容量不明のノードは除外し、全不明時はnull。 */
    @SerialName("cpuCapacityCores") val cpuCapacityCores: Double? = null,
    /** グループ内ノードのメモリ容量合計(GiB)。同上。 */
    @SerialName("memCapacityGiB") val memCapacityGiB: Double? = null
)

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728)のレスポンス。
 * MCPクライアントが `/.well-known/oauth-protected-resource` を叩いて
 * 認可サーバー(Keycloak)を発見するために使う。フィールド名はRFCどおりsnake_case。
 */
@Serializable
data class ProtectedResourceMetadata(
    @SerialName("resource") val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String> = emptyList(),
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

    // シャットダウン/再起動はDrain+SSH実行で数十秒〜数分かかるため、リクエストを
    // ブロックせずバックグラウンドで実行する(進行状況はGET /api/serversの
    // 自動更新で確認できる。issue #58)。
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopping) { backgroundScope.cancel() }
    val logger = environment.log

    // issue #183: Keycloak client role(admin-panel)によるRBAC認証(Ktor Authentication/JWT)。
    // 検証対象は signature/JWKS・iss・exp・aud(admin-panel)・必要時 azp。
    // 認可は各routeの requireRole(...) で行う(未認証401 / 権限不足403)。
    val rbacConfig = RbacConfig.fromEnv()
    val jwksProvider = RefreshingJwkProvider(rbacConfig.jwksUrl)
    install(Authentication) {
        keycloakJwt(rbacConfig, jwksProvider)
        mcpJwt(rbacConfig, jwksProvider)
    }

    // issue #183: 起動時にロール判定が可能か(JWKS到達・aud要件)をログに残す。
    // 失敗しても起動は継続し、検証はリクエスト毎に再試行される。
    logRbacStartupStatus(rbacConfig, jwksProvider, logger, backgroundScope)

    // admin-panel#64: CI向けトークン発行の許可設定(ci_token_policy)用DB。MariaDBが未設定の
    // 環境(ローカル開発等)では機能ごと無効化し、他の機能には影響させない。
    if (isDatabaseConfigured) {
        initDatabaseSchema()
        runBlocking { seedCiTokenPolicyIfEmpty() }
    }

    // MCP Serverのツール登録(issue #200)
    net.kigawa.admin.server.mcp.McpServerInstance.initialize()

    routing {
        get("/health") {
            call.respondText("OK")
        }

        // Kobwebが生成する本番JS(admin.js)も、実開発サーバー(`kobweb run`)のライブ
        // リロード通知用SSEエンドポイントに無条件で接続を試みる設定を持つ。この静的
        // エクスポートを自前ホストしている構成ではその機能自体は不要だが、エンドポイント
        // が存在しないと404でEventSourceのonerrorが発火しコンソールにエラーが出続ける
        // ため、接続だけ受け付けて何もイベントを送らないダミー実装で解消する。
        //
        // 最初のバージョンは何もバイトを送らず接続を保持するだけだったが、Cloudflare側が
        // データの流れないアイドル接続を約10秒でタイムアウトさせ502を返すことが判明した
        // (実機で確認)。SSEコメント行を接続直後と一定間隔で送り続け、中間プロキシから
        // 「生きている」接続として扱われるようにする。
        get("/api/kobweb-status") {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                try {
                    while (true) {
                        writeStringUtf8(": keep-alive\n\n")
                        flush()
                        delay(15_000)
                    }
                } catch (e: Exception) {
                    // クライアント切断時に書き込みが失敗するのは正常なので握りつぶす
                }
            }
        }

        // CI-facing broker: custom action -> admin-panel -> GitHub App. Authenticated with the
        // caller's own GitHub Actions OIDC token (verified against GitHub's published JWKS)
        // instead of a shared secret, so there is no static credential to distribute or leak.
        // The verified `repository` claim — not client input — is checked against
        // ciTokenPolicy to decide what the caller may request.
        post("/api/github-app/ci-token") {
            val presented = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            val claims = presented?.let { GithubActionsOidc.verify(httpClient, it, adminPanelActionsAudience) }
            val callerRepository = claims?.repository
            if (callerRepository.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing OIDC token"))
                return@post
            }
            if (!GithubApp.isConfigured) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "GitHub App not configured"))
                return@post
            }
            if (!isDatabaseConfigured) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "CI token policy database not configured"))
                return@post
            }
            val request = call.receive<GithubCiTokenRequest>()
            val owner = request.owner ?: "kigawa-net"
            val policyError = checkCiTokenRequest(
                callerRepository = callerRepository,
                requestedOwner = owner,
                requestedRepositories = request.repositories,
                requestedPermissions = request.permissions
            )
            if (policyError != null) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to policyError))
                return@post
            }
            val installation = GithubApp.getOrgInstallation(httpClient, owner)
            call.respond(
                GithubApp.createInstallationToken(
                    httpClient,
                    installation.id,
                    repositories = request.repositories,
                    permissions = request.permissions
                )
            )
        }

        // 認証が必要なAPI(issue #183): Keycloakのclient role(viewer/operator/admin)で認可する。
        // 認証方式が別の /health・/api/kobweb-status・/api/github-app/ci-token は外側に置く。
        authenticate("keycloak") {
            get("/api/traffic") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get

                val rangeMinutes = call.request.queryParameters["rangeMinutes"]?.toIntOrNull()?.coerceIn(5, 1440) ?: 60
                call.respond(queryTraffic(httpClient, rangeMinutes))
            }

            get("/api/network-topology") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get

                // ユーザーがアクセス可能な組織IDを取得(RBACのadminロール保持者は全アクセス可)。
                // 組織スコープの絞り込みはRBACとは別の概念(issue #183)として維持する。
                val allowedOrgIds = if (principal.roles.contains(AdminRole.ADMIN)) {
                    null // null = 全組織許可
                } else {
                    listMyOrganizations(httpClient, principal.userId)
                        ?.organizations?.map { it.id }?.toSet() ?: emptySet()
                }

                call.respond(loadNetworkTopology(httpClient, allowedOrgIds))
            }

            get("/api/servers") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get

                val statuses = fetchServerStatuses()
                if (statuses == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "server status unavailable"))
                } else {
                    call.respond(statuses)
                }
            }

            get("/api/servers/{name}/pods") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get

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
                val principal = requireRole(AdminRole.OPERATOR) ?: return@post
                val nodeName = call.parameters["name"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@post
                }
                auditLog(principal, operation = "server.cordon", target = nodeName, result = "accepted")
                call.respond(setNodeSchedulable(nodeName, schedulable = false))
            }

            post("/api/servers/{name}/uncordon") {
                val principal = requireRole(AdminRole.OPERATOR) ?: return@post
                val nodeName = call.parameters["name"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@post
                }
                auditLog(principal, operation = "server.uncordon", target = nodeName, result = "accepted")
                call.respond(setNodeSchedulable(nodeName, schedulable = true))
            }

            post("/api/servers/{name}/drain") {
                val principal = requireRole(AdminRole.OPERATOR) ?: return@post
                val nodeName = call.parameters["name"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@post
                }
                auditLog(principal, operation = "server.drain", target = nodeName, result = "accepted")
                call.respond(drainNode(nodeName))
            }

            // ノード自体の電源操作(SSH経由)。Cordon/Drainを行った上で、実際にシャットダウン/
            // 再起動する。drainTimeoutSecondsの間だけPodの退避完了を待ち、タイムアウトしても
            // 処理は続行する(無限に待たない)。
            post("/api/servers/{name}/graceful-shutdown") {
                val principal = requireRole(AdminRole.OPERATOR) ?: return@post
                val nodeName = call.parameters["name"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@post
                }
                val request = try {
                    call.receive<GracefulShutdownRequestDto>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                    return@post
                }
                val timeout = request.drainTimeoutSeconds.coerceIn(0, 1800)
                backgroundScope.launch {
                    val result = gracefulNodeShutdown(nodeName, timeout, reboot = false)
                    if (!result.success) logger.warn("graceful-shutdown of $nodeName failed: ${result.message}")
                    auditLog(
                        principal,
                        operation = "server.graceful-shutdown",
                        target = nodeName,
                        result = if (result.success) "succeeded" else "failed",
                        detail = result.message
                    )
                }
                auditLog(
                    principal,
                    operation = "server.graceful-shutdown",
                    target = nodeName,
                    result = "started",
                    detail = "drainTimeoutSeconds=$timeout"
                )
                call.respond(ActionResultDto(true, "シャットダウン処理を開始しました。進行状況は一覧の自動更新で確認できます。"))
            }

            post("/api/servers/{name}/graceful-reboot") {
                val principal = requireRole(AdminRole.OPERATOR) ?: return@post
                val nodeName = call.parameters["name"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@post
                }
                val request = try {
                    call.receive<GracefulShutdownRequestDto>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                    return@post
                }
                val timeout = request.drainTimeoutSeconds.coerceIn(0, 1800)
                backgroundScope.launch {
                    val result = gracefulNodeShutdown(nodeName, timeout, reboot = true)
                    if (!result.success) logger.warn("graceful-reboot of $nodeName failed: ${result.message}")
                    auditLog(
                        principal,
                        operation = "server.graceful-reboot",
                        target = nodeName,
                        result = if (result.success) "succeeded" else "failed",
                        detail = result.message
                    )
                }
                auditLog(
                    principal,
                    operation = "server.graceful-reboot",
                    target = nodeName,
                    result = "started",
                    detail = "drainTimeoutSeconds=$timeout"
                )
                call.respond(ActionResultDto(true, "再起動処理を開始しました。進行状況は一覧の自動更新で確認できます。"))
            }

            delete("/api/pods/{namespace}/{name}") {
                val principal = requireRole(AdminRole.OPERATOR) ?: return@delete
                val namespace = call.parameters["namespace"]
                val name = call.parameters["name"]
                if (namespace.isNullOrBlank() || name.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing namespace or name"))
                    return@delete
                }
                auditLog(principal, operation = "pod.delete", target = "$namespace/$name", result = "accepted")
                call.respond(deletePod(namespace, name))
            }

            // ユーザー管理(kigawa-net realm・adminロールの管理者のみ)。Keycloak Admin REST APIを
            // 専用サービスアカウント(client_credentials)経由で呼ぶ。
            // サービスアカウント未設定の場合は503を返す。
            get("/api/users") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@get
                val users = listKeycloakUsers(httpClient)
                if (users == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "user list unavailable"))
                } else {
                    call.respond(users)
                }
            }

            post("/api/users") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@post
                val request = try {
                    call.receive<CreateUserRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                    return@post
                }
                val created = createKeycloakUser(httpClient, request)
                auditLog(
                    principal,
                    operation = "user.create",
                    target = request.username,
                    result = if (created.success) "accepted" else "failed",
                    detail = created.message
                )
                call.respond(created)
            }

            delete("/api/users/{id}") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@delete
                val userId = call.parameters["id"]
                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                    return@delete
                }
                val deleted = deleteKeycloakUser(httpClient, userId)
                auditLog(
                    principal,
                    operation = "user.delete",
                    target = userId,
                    result = if (deleted.success) "accepted" else "failed",
                    detail = deleted.message
                )
                call.respond(deleted)
            }

            post("/api/users/{id}/enable") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@post
                val userId = call.parameters["id"]
                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                    return@post
                }
                val enabledResult = setKeycloakUserEnabled(httpClient, userId, enabled = true)
                auditLog(
                    principal,
                    operation = "user.enable",
                    target = userId,
                    result = if (enabledResult.success) "accepted" else "failed",
                    detail = enabledResult.message
                )
                call.respond(enabledResult)
            }

            post("/api/users/{id}/disable") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@post
                val userId = call.parameters["id"]
                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing user id"))
                    return@post
                }
                val disabled = setKeycloakUserEnabled(httpClient, userId, enabled = false)
                auditLog(
                    principal,
                    operation = "user.disable",
                    target = userId,
                    result = if (disabled.success) "accepted" else "failed",
                    detail = disabled.message
                )
                call.respond(disabled)
            }

            post("/api/users/{id}/reset-password") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@post
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
                val reset = resetKeycloakUserPassword(httpClient, userId, request.newPassword, request.temporary)
                auditLog(
                    principal,
                    operation = "user.reset-password",
                    target = userId,
                    result = if (reset.success) "accepted" else "failed",
                    detail = reset.message
                )
                call.respond(reset)
            }

            // 組織管理(対象データはkigawa-net realm)。全組織の一覧・削除はadminロールの
            // 管理者限定、作成・自分が所属する組織の閲覧・メンバー管理はkigawa-net realmの
            // 一般ユーザーも行える。Keycloak Organizations REST APIを専用サービスアカウント
            // (client_credentials)経由で呼ぶ。
            get("/api/organizations") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@get
                val organizations = listOrganizations(httpClient)
                if (organizations == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to (organizationApiUnavailableReason() ?: "組織一覧を取得できませんでした"))
                    )
                } else {
                    call.respond(organizations)
                }
            }

            // 組織の作成はkigawa-net realmにログインしているユーザーなら誰でも行える。
            // 一般ユーザーが作成した場合は、作成者自身を自動的にその組織のメンバーとして登録する
            // (でなければ作成した本人がその組織を一覧にも出せず操作もできなくなってしまう)。
            post("/api/organizations") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@post
                val request = try {
                    call.receive<CreateOrganizationRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                    return@post
                }
                val result = createOrganization(httpClient, request)
                if (result.success && !principal.roles.contains(AdminRole.ADMIN)) {
                    // 非adminの作成者を自動的にメンバーへ登録する(でなければ作成した本人が
                    // その組織を一覧にも出せず操作もできなくなってしまう)。adminロールは
                    // 全組織を扱えるため登録しない。
                    val orgId = findOrganizationIdByName(httpClient, request.name)
                    if (orgId != null) {
                        addOrganizationMember(httpClient, orgId, principal.userId)
                    }
                }
                auditLog(
                    principal,
                    operation = "organization.create",
                    target = request.name,
                    result = if (result.success) "accepted" else "failed"
                )
                call.respond(result)
            }

            delete("/api/organizations/{id}") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@delete
                val orgId = call.parameters["id"]
                if (orgId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                    return@delete
                }
                val deleted = deleteOrganization(httpClient, orgId)
                auditLog(
                    principal,
                    operation = "organization.delete",
                    target = orgId,
                    result = if (deleted.success) "accepted" else "failed",
                    detail = deleted.message
                )
                call.respond(deleted)
            }

            get("/api/organizations/{id}/members") {
                val orgId = call.parameters["id"]
                if (orgId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                    return@get
                }
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                // RBACとは別に、組織スコープ(adminロール or メンバー本人)で絞り込む(issue #183)
                if (!canManageOrganization(httpClient, principal, orgId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not a member of this organization"))
                    return@get
                }
                val members = listOrganizationMembers(httpClient, orgId)
                if (members == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to (organizationApiUnavailableReason() ?: "メンバー一覧を取得できませんでした"))
                    )
                } else {
                    call.respond(members)
                }
            }

            post("/api/organizations/{id}/members") {
                val orgId = call.parameters["id"]
                if (orgId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id"))
                    return@post
                }
                val principal = requireRole(AdminRole.VIEWER) ?: return@post
                // RBACとは別に、組織スコープ(adminロール or メンバー本人)で絞り込む(issue #183)
                if (!canManageOrganization(httpClient, principal, orgId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not a member of this organization"))
                    return@post
                }
                val request = try {
                    call.receive<AddOrganizationMemberRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request body"))
                    return@post
                }
                val result = addOrganizationMember(httpClient, orgId, request.userId)
                auditLog(
                    principal,
                    operation = "organization.member.add",
                    target = "$orgId/${request.userId}",
                    result = if (result.success) "accepted" else "failed"
                )
                call.respond(result)
            }

            delete("/api/organizations/{id}/members/{userId}") {
                val orgId = call.parameters["id"]
                val userId = call.parameters["userId"]
                if (orgId.isNullOrBlank() || userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing organization id or user id"))
                    return@delete
                }
                val principal = requireRole(AdminRole.VIEWER) ?: return@delete
                // RBACとは別に、組織スコープ(adminロール or メンバー本人)で絞り込む(issue #183)
                if (!canManageOrganization(httpClient, principal, orgId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not a member of this organization"))
                    return@delete
                }
                val result = removeOrganizationMember(httpClient, orgId, userId)
                auditLog(
                    principal,
                    operation = "organization.member.remove",
                    target = "$orgId/$userId",
                    result = if (result.success) "accepted" else "failed"
                )
                call.respond(result)
            }

            // 組織一覧のうち、呼び出したユーザー自身がメンバーになっているものだけを返す
            // (一般ユーザー向け。全組織を見せるadmin専用の GET /api/organizations とは別)。
            get("/api/organizations/mine") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                // subはJWTのsubクレームで得る(userinfoへの依存を断つ。issue #183)
                val organizations = listMyOrganizations(httpClient, principal.userId)
                if (organizations == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to (organizationApiUnavailableReason() ?: "組織一覧を取得できませんでした"))
                    )
                } else {
                    call.respond(organizations)
                }
            }

            get("/api/organizations/users") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val query = call.request.queryParameters["query"]
                if (query.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing query"))
                    return@get
                }
                val users = searchKigawaNetUsers(httpClient, query)
                if (users == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to (organizationApiUnavailableReason() ?: "ユーザー検索に失敗しました"))
                    )
                } else {
                    call.respond(users)
                }
            }

            // 物理ホスト(Proxmox)一覧+ハードウェア情報。管理者限定。VM/ディスク/PCI等の詳細は
            // 呼び出し回数が多く遅くなりがちなため/api/infrastructure/detailsに分離しており、
            // クライアントはこちらを先に表示してから詳細を非同期に読み込む。
            get("/api/infrastructure") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                call.respond(fetchInfrastructureHosts())
            }

            // 物理ホストごとのVM一覧・ディスク・PCIデバイス詳細、およびVMとして見つからなかった
            // K8sノード(物理専用ノード)一覧。管理者限定。
            // composeAppとresource-usage-grouped集約が利用する一括取得用。siteのインフラ構成
            // ページは下のホスト×カテゴリ単位の細粒度エンドポイントを使い、届いた部分から順次描画する。
            get("/api/infrastructure/details") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                call.respond(fetchInfrastructureDetails())
            }

            // ホスト×カテゴリ単位の細粒度取得(issue #158)。フロントはこれらを並列に叩き、
            // 届いた部分から順次描画するため、低速なカテゴリが他を道連れにしない。
            // 失敗時は空コンテンツで200を返す(既存のグレースフルデグラデーション方針)。
            get("/api/infrastructure/hosts/{host}/vms") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchSingleHostVms(hostName))
            }

            get("/api/infrastructure/hosts/{host}/disks") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchSingleHostDisks(hostName))
            }

            get("/api/infrastructure/hosts/{host}/pci") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchSingleHostPciDevices(hostName))
            }

            get("/api/infrastructure/hosts/{host}/hw-status") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchSingleHostHwStatus(hostName))
            }

            // Proxmox物理ホストのデバイス空きスロット調査(admin-panel#156)。SSHでホストに
            // 直接入りdmidecode/lsblkで取得する。認証情報(Bitwarden同期の
            // admin-panel-proxmox-ssh)未設定の間は503を返すのみで、他機能には影響しない。
            get("/api/infrastructure/hosts/{host}/slots") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                if (!isProxmoxSshConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Proxmox SSH not configured"))
                    return@get
                }
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchHostSlotInventory(hostName))
            }

            // k8sノードのデバイス空きスロット調査(Proxmoxホスト向けのフォローアップ)。
            // ノードIPはKubernetes APIのInternalIPで解決し、認証情報はNODE_SSH_*を使う。
            // KVMゲスト等の仮想ノードは物理スロットの概念がないためvirtualized=trueで返し、
            // フロント側で表示を抑止する。ionosゲートウェイ(クラウドVPS)は対象外。
            // 未設定の間は503を返すのみで、他機能には影響しない。
            get("/api/infrastructure/nodes/{node}/slots") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                if (!isNodeSshConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Node SSH not configured"))
                    return@get
                }
                val nodeName = call.parameters["node"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@get
                }
                call.respond(fetchNodeSlotInventory(nodeName))
            }

            // マウントポイント別ディスク使用率(admin-panel#148)。Prometheusにnode-exporterが
            // なく`node_filesystem_*`が存在しないため時系列グラフにはせず、SSHでdfを実行した
            // 現在値を返す。空きスロット取得とは独立にしているため、フロントは両者を並列に
            // 叩ける(#158の「低速なカテゴリが他を道連れにしない」方針と同じ)。
            // 認証情報(Proxmox SSH)未設定の間は503を返すのみで、他機能には影響しない。
            get("/api/infrastructure/hosts/{host}/disk-usage") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                if (!isProxmoxSshConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Proxmox SSH not configured"))
                    return@get
                }
                val hostName = call.parameters["host"]
                if (hostName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing host name"))
                    return@get
                }
                call.respond(fetchHostDiskUsage(hostName))
            }

            // K8sノードのマウントポイント別ディスク使用率(admin-panel#148)。ノードIPは
            // Kubernetes APIのInternalIPで解決し、認証情報はNODE_SSH_*を使う。
            // 未設定の間は503を返すのみで、他機能には影響しない。
            get("/api/infrastructure/nodes/{node}/disk-usage") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                if (!isNodeSshConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Node SSH not configured"))
                    return@get
                }
                val nodeName = call.parameters["node"]
                if (nodeName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing node name"))
                    return@get
                }
                call.respond(fetchNodeDiskUsage(nodeName))
            }

            // インフラのリソース利用量の時系列(物理ホスト=Proxmox rrddata / K8sノード=Prometheus
            // cAdvisor)。グラフ表示用(issue #132)。rangeMinutes は15〜1440(既定60)で、グラフの
            // 描画点を抑えるためトラフィック時系列と同じく最大120点程度に丸める。
            get("/api/infrastructure/resource-usage") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val rangeMinutes =
                    call.request.queryParameters["rangeMinutes"]?.toIntOrNull()?.coerceIn(15, 1440) ?: 60

                val (physicalHosts, k8sNodes) = coroutineScope<Pair<Map<String, PhysicalHostUsageDto>, Map<String, K8sNodeUsageDto>>> {
                    val physicalDeferred = async { fetchPhysicalHostUsage(rangeMinutes) }
                    val k8sDeferred = async { fetchNodeResourceUsageSeries(rangeMinutes) }
                    physicalDeferred.await() to k8sDeferred.await()
                }

                call.respond(
                    InfrastructureResourceUsageResponse(
                        rangeMinutes = rangeMinutes,
                        physicalHosts = physicalHosts,
                        k8sNodes = k8sNodes
                    )
                )
            }

            // インフラのリソース使用量をグルーピングして集約した時系列(issue #147)。
            // role / pciType / physicalHost の3軸で集約し、フロント側でタブ切り替えできるようにする。
            get("/api/infrastructure/resource-usage-grouped") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@get
                val rangeMinutes =
                    call.request.queryParameters["rangeMinutes"]?.toIntOrNull()?.coerceIn(15, 1440) ?: 60

                // ノード単位の生データを取得してから、各軸で集約する
                val nodeSeriesRaw = fetchNodeResourceUsageSeries(rangeMinutes)
                val infraDetails = fetchInfrastructureDetails()  // Proxmoxホスト-VMマッピング用

                // K8sNodeUsageDto を NodeResourceUsageSeries に変換
                val nodeSeries = nodeSeriesRaw.mapValues { (_, dto) ->
                    NodeResourceUsageSeries(
                        cpuCores = dto.cpuCores.map { it.timestampSeconds to it.value },
                        memGiB = dto.memGiB.map { it.timestampSeconds to it.value }
                    )
                }
                // グループ容量合計用にノード単位の容量を保持(容量不明ノードは除外)
                val nodeCapacity = nodeSeriesRaw.mapNotNull { (name, dto) ->
                    val cpu = dto.cpuCapacityCores?.toDouble()
                    val mem = dto.memCapacityGiB
                    if (cpu == null && mem == null) null else name to ((cpu ?: 0.0) to (mem ?: 0.0))
                }.toMap()

                // ノード名 → PCIタイプ / 物理ホスト のマッピングを作成
                val nodeToPciType = mutableMapOf<String, String>()
                val nodeToPhysicalHost = mutableMapOf<String, String>()
                val nodeToRole = mutableMapOf<String, String>()

                // fetchServerStatuses() で role を取得
                fetchServerStatuses()?.servers?.forEach { server ->
                    nodeToRole[server.name] = server.role
                }

                // infraDetails.hostDetails から VM→物理ホスト、PCIタイプを解決
                infraDetails.hostDetails.forEach { (hostName, details) ->
                    details.vms.forEach { vm ->
                        nodeToPhysicalHost[vm.name] = hostName
                        // PCIタイプは最初のデバイスから代表的なものを決定
                        val pciType = details.pciDevices.firstOrNull()?.let { classifyPciType(it) } ?: "Other"
                        nodeToPciType[vm.name] = pciType
                    }
                    // standaloneNodes も対象
                    details.vms.forEach { vm ->
                        if (!nodeToPhysicalHost.containsKey(vm.name)) {
                            nodeToPhysicalHost[vm.name] = hostName
                        }
                    }
                }
                infraDetails.standaloneNodes.forEach { node ->
                    nodeToPhysicalHost[node.name] = "standalone"
                    nodeToPciType[node.name] = "Other"
                }

                // 各軸で集約
                val byRole = aggregateByKey(nodeSeries, nodeToRole, nodeCapacity)
                val byPciType = aggregateByKey(nodeSeries, nodeToPciType, nodeCapacity)
                val byPhysicalHost = aggregateByKey(nodeSeries, nodeToPhysicalHost, nodeCapacity)

                call.respond(
                    GroupedResourceUsageResponse(
                        rangeMinutes = rangeMinutes,
                        byRole = byRole,
                        byPciType = byPciType,
                        byPhysicalHost = byPhysicalHost
                    )
                )
            }

            // GitHub App (kigawa-net, app_id 4316503) operation: mint scoped installation tokens
            // in place of the long-lived org PAT. Admin-only, since a minted token can act with up
            // to the App's full contents:write permission.
            get("/api/github-app/installations") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@get
                if (!GithubApp.isConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "GitHub App not configured"))
                    return@get
                }
                call.respond(GithubApp.listInstallations(httpClient))
            }

            post("/api/github-app/installations/{id}/token") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@post
                if (!GithubApp.isConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "GitHub App not configured"))
                    return@post
                }
                val installationId = call.parameters["id"]?.toLongOrNull()
                if (installationId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid installation id"))
                    return@post
                }
                val request = call.receive<GithubInstallationTokenRequest>()
                auditLog(
                    principal,
                    operation = "github-app.installation-token",
                    target = installationId.toString(),
                    result = "accepted"
                )
                call.respond(
                    GithubApp.createInstallationToken(
                        httpClient,
                        installationId,
                        repositories = request.repositories,
                        permissions = request.permissions
                    )
                )
            }


            // admin-panel#64: 上のci-tokenブローカーが参照する呼び出し元リポジトリ別許可設定
            // (ci_token_policy)を管理画面から追加・編集・削除できるようにするCRUD。管理者限定。
            get("/api/github-app/ci-policy") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@get
                if (!isDatabaseConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "CI token policy database not configured"))
                    return@get
                }
                call.respond(listCiTokenPolicies())
            }

            put("/api/github-app/ci-policy/{callerRepository}") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@put
                if (!isDatabaseConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "CI token policy database not configured"))
                    return@put
                }
                val callerRepository = call.parameters["callerRepository"]
                if (callerRepository.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing callerRepository"))
                    return@put
                }
                val request = call.receive<CiTokenPolicyEntryDto>()
                if (request.callerRepository != callerRepository) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "callerRepository mismatch"))
                    return@put
                }
                upsertCiTokenPolicy(request)
                auditLog(principal, operation = "ci-policy.upsert", target = callerRepository, result = "accepted")
                call.respond(request)
            }

            delete("/api/github-app/ci-policy/{callerRepository}") {
                val principal = requireRole(AdminRole.ADMIN) ?: return@delete
                if (!isDatabaseConfigured) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "CI token policy database not configured"))
                    return@delete
                }
                val callerRepository = call.parameters["callerRepository"]
                if (callerRepository.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing callerRepository"))
                    return@delete
                }
                val deleted = deleteCiTokenPolicy(callerRepository)
                auditLog(
                    principal,
                    operation = "ci-policy.delete",
                    target = callerRepository,
                    result = if (deleted) "accepted" else "failed"
                )
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("deleted" to "true"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                }
            }
        }

        // MCP endpoint (issue #200): Streamable HTTP transport for MCP
        // Requires authentication with viewer role or higher
        authenticate("mcp") {
            post("/mcp") {
                val principal = requireRole(AdminRole.VIEWER) ?: return@post
                val mcpServer = net.kigawa.admin.server.mcp.McpServerInstance.getInstance()
                val requestBody = call.receive<McpRequest>()
                val response = mcpServer.handleCall(call, principal, requestBody)
                call.respond(response)
            }
        }

        // OAuth 2.0 Protected Resource Metadata (RFC 9728)
        // MCPクライアントが認可サーバーを発見するために使用
        //
        // Mapではなく専用のデータクラスで返す。MapだとString/Listが混在した
        // 要素の型推定ができず、kotlinx.serializationが500を吐く(実機で確認済み)。
        get("/.well-known/oauth-protected-resource") {
            call.respond(
                ProtectedResourceMetadata(
                    resource = "https://admin.kigawa.net/mcp",
                    authorizationServers = listOf("https://user.kigawa.net/realms/kigawa-net"),
                    scopesSupported = listOf("openid", "profile", "email", "mcp:admin-panel"),
                    bearerMethodsSupported = listOf("header"),
                )
            )
        }
    }
}

/**
 * 組織の操作(メンバー閲覧・追加・削除)を許可するか判定する(issue #183)。
 * RBACとOrganization scopeは別概念で、ここは Organization scope 側の判定になる:
 * - adminロール(RBAC)を持つ人は常に許可
 * - それ以外は、指定組織のメンバー本人(subがメンバーシップに一致)のみ許可
 * (従来は isValidAdminToken() が全ログインユーザーでtrueになっていたため、
 * 誰でも全組織のメンバー操作ができてしまう状態だった)
 */
private suspend fun canManageOrganization(
    client: HttpClient,
    principal: AdminPrincipal,
    orgId: String
): Boolean {
    if (principal.roles.contains(AdminRole.ADMIN)) return true
    val members = listOrganizationMembers(client, orgId) ?: return false
    return members.members.any { it.id == principal.userId }
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

/**
 * PCIデバイスから代表的なタイプを分類する(issue #147)。
 * 最初のPCIデバイスのクラス名から GPU / Storage / Network / Other を判定。
 */
private fun classifyPciType(device: InfraPciDeviceDto): String {
    val className = device.pciClass?.lowercase() ?: return "Other"
    return when {
        className.contains("3d") || className.contains("vga") || className.contains("display") || className.contains("gpu") || className.contains("accelerator") -> "GPU"
        className.contains("storage") || className.contains("raid") || className.contains("sata") || className.contains("nvme") || className.contains("scsi") -> "Storage"
        className.contains("network") || className.contains("ethernet") || className.contains("infiniband") || className.contains("fibre") -> "Network"
        else -> "Other"
    }
}

/**
 * ノード単位の時系列データを指定キー(role/pciType/physicalHost)で集約する(issue #147)。
 * 各タイムスタンプごとに、同一キーのノードの値を合計(CPUはコア数合計、メモリはGiB合計)する。
 */
/**
 * ノード単位の時系列データを指定キー(role/pciType/physicalHost)で集約する(issue #147)。
 * 各タイムスタンプごとに、同一キーのノードの値を合計(CPUはコア数合計、メモリはGiB合計)する。
 */
private fun aggregateByKey(
    nodeSeries: Map<String, NodeResourceUsageSeries>,
    nodeToKey: Map<String, String>,
    nodeCapacity: Map<String, Pair<Double, Double>> = emptyMap()
): Map<String, GroupedSeries> {
    // キーごとのノード名リスト
    val keyToNodes = nodeToKey.entries.groupBy { it.value }.mapValues { (_, entries) -> entries.map { it.key } }

    return keyToNodes.mapValues { (key, nodes) ->
        val relevantSeries = nodes.mapNotNull { nodeSeries[it] }.filter { it.cpuCores.isNotEmpty() || it.memGiB.isNotEmpty() }
        // グループ内ノードの容量合計(容量不明ノードは除外し、全不明時はnull)
        val capacities = nodes.mapNotNull { nodeCapacity[it] }
        val cpuCapacitySum = capacities.sumOf { it.first }.takeIf { capacities.any { it.first > 0 } }
        val memCapacitySum = capacities.sumOf { it.second }.takeIf { capacities.any { it.second > 0 } }
        if (relevantSeries.isEmpty()) {
            GroupedSeries(
                nodeCount = nodes.size,
                nodeNames = nodes,
                cpuCapacityCores = cpuCapacitySum,
                memCapacityGiB = memCapacitySum
            )
        } else {
            // タイムスタンプごとに値を合計
            val allCpuPoints = relevantSeries.flatMap { it.cpuCores }.groupBy { it.first }.mapValues { (_, points) ->
                points.sumOf { it.second }
            }
            val allMemPoints = relevantSeries.flatMap { it.memGiB }.groupBy { it.first }.mapValues { (_, points) ->
                points.sumOf { it.second }
            }
            val cpuSorted = allCpuPoints.toList().sortedBy { it.first }.map { (ts, v) -> ResourceUsagePointDto(ts, v) }
            val memSorted = allMemPoints.toList().sortedBy { it.first }.map { (ts, v) -> ResourceUsagePointDto(ts, v) }
            GroupedSeries(
                cpuCores = cpuSorted,
                memGiB = memSorted,
                nodeCount = nodes.size,
                nodeNames = nodes,
                cpuCapacityCores = cpuCapacitySum,
                memCapacityGiB = memCapacitySum
            )
        }
    }
}
