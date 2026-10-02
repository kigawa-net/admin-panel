package net.kigawa.admin.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keycloakのkigawa-netレルムでの認証を扱う。
 * issue #155で管理用realm(manage)からkigawa-netへ移行した。
 * issue #183で認可はadmin-panelのclient role(viewer/operator/admin)によるRBACへ移行し、
 * このファイルは「ロールの取得・保持」だけを担う。表示に使う判定は [rbacPermissions] で
 * 作り、実際の権限の境界はサーバー側(Ktor APIのrequireRole)で再判定される。
 * kigawa-net realmは誰でもセルフ登録できる(registrationAllowed=true)ため、
 * ロールが確認できない間は権限なし(安全側)として扱う。
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null
)

@Serializable
data class UserInfoResponse(
    @SerialName("sub") val sub: String,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("name") val name: String? = null,
    // ロールはKeycloakのマッパー設定により形式が揺れるため、あえて型を固定しない。
    // (realm_access は realmロールのため参照せず、client roleのみを [rbacRoles] で抜き出す)
    @SerialName("roles") val roles: JsonElement? = null,
    @SerialName("resource_access") val resourceAccess: JsonElement? = null
)

/**
 * admin-panelのKeycloak client role(issue #183)。
 * Keycloak側ではcomposite roleで admin ⊃ operator ⊃ viewer の包含を解決済み。
 */
const val ROLE_VIEWER = "viewer"
const val ROLE_OPERATOR = "operator"
const val ROLE_ADMIN = "admin"

private val KNOWN_ROLES = setOf(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN)

/**
 * メニュー・ボタン・ページの表示制御に使う権限(issue #183)。
 *
 * あくまでUX用でありセキュリティ境界ではない。最終的な認可は必ずサーバー側の
 * Ktor API(各routeのrequireRole)が同じロールで判定する。
 */
data class RbacPermissions(
    /** サーバー・ネットワーク・インフラ・メトリクスの閲覧(viewer) */
    val canViewInfrastructure: Boolean,
    /** Cordon/Drain/Pod再起動・電源操作(operator) */
    val canOperateServers: Boolean,
    /** ユーザー管理・組織削除など(admin) */
    val canManageUsers: Boolean,
    /** GitHub App token発行・CI token policy(admin) */
    val canManageGithubApp: Boolean,
    /** 組織の一覧(全件)表示・削除など、adminロールそのもの */
    val isAdmin: Boolean
)

/**
 * ロール集合から表示制御用の権限を計算する。
 * composite roleで包含は解決済みだが、保存形式の移行直後などロールが1つだけ
 * 入ってきてもマトリクス通りになるよう本側でも階層を展開する。
 */
fun rbacPermissions(roles: Set<String>): RbacPermissions {
    val hasAdmin = ROLE_ADMIN in roles
    val hasOperator = hasAdmin || ROLE_OPERATOR in roles
    val hasViewer = hasOperator || ROLE_VIEWER in roles
    return RbacPermissions(
        canViewInfrastructure = hasViewer,
        canOperateServers = hasOperator,
        canManageUsers = hasAdmin,
        canManageGithubApp = hasAdmin,
        isAdmin = hasAdmin
    )
}

/** [element]が既知のロール名を含む場合に取り出す(未知の値は無視する)。 */
private fun collectKnownRoles(element: JsonElement?, into: MutableSet<String>) {
    when (element) {
        is JsonArray -> element.forEach { collectKnownRoles(it, into) }
        is JsonObject -> element.forEach { (key, value) ->
            if (key in KNOWN_ROLES) into += key else collectKnownRoles(value, into)
        }
        is JsonPrimitive -> if (element.content in KNOWN_ROLES) into += element.content
        null -> Unit
        else -> Unit
    }
}

/**
 * userinfo / access tokenのロール表現から admin-panel のロール集合を抜き出す。
 *
 * - トップレベルの `roles`: KeycloakのUser Client Roleマッパー(multivalued)が出す形式
 * - `resource_access["admin-panel"].roles`: access token で issue が指定する形式
 *
 * `realm_access` は realmロールでありclient roleと名前が衝突しうるため参照しない。
 * ロールが無い・形式が不明な場合は空集合(= 権限なし・安全側)。
 */
fun rolesFromClaims(roles: JsonElement?, resourceAccess: JsonElement?): Set<String> {
    val result = mutableSetOf<String>()
    collectKnownRoles(roles, result)
    (resourceAccess as? JsonObject)?.get("admin-panel")?.let { collectKnownRoles(it, result) }
    return result
}

/** userinfo応答全体から admin-panel のロール集合を抜き出す。 */
fun UserInfoResponse.rbacRoles(): Set<String> = rolesFromClaims(roles, resourceAccess)

interface KeycloakAuthConfig {
    val serverUrl: String
    val clientId: String

    // issue #155で管理用realm(manage)からkigawa-netへ移行した
    val realm: String
        get() = "kigawa-net"
}

object DefaultKeycloakConfig : KeycloakAuthConfig {
    override val serverUrl: String = "https://user.kigawa.net"
    override val clientId: String = "admin-panel"
}

private fun KeycloakAuthConfig.authUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/auth"
private fun KeycloakAuthConfig.tokenUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/token"
private fun KeycloakAuthConfig.userInfoUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/userinfo"

expect fun createHttpClient(): HttpClient

/** Generates a cryptographically secure random string usable as a PKCE code verifier / OAuth state. */
expect fun secureRandomString(length: Int): String

/** SHA-256 hashes [input] and returns the result as Base64url (no padding), per RFC 7636. */
expect fun sha256Base64Url(input: String): String

expect fun currentTimeMillis(): Long

/** Refresh this long before actual expiry, to allow for request latency and clock skew. */
private const val REFRESH_MARGIN_MS = 30_000L

data class PersistedSession(
    val username: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    // 最後に userinfo で確認できた admin-panel のclient roles(viewer/operator/admin)。
    // 取得できない間は空(= 権限なし・安全側)。表示用のみで、境界はサーバー側のRBAC。
    val roles: List<String> = emptyList()
)

/**
 * Persists the session across app restarts (Android: SharedPreferences, Desktop: the OS-native
 * java.util.prefs store), mirroring the web client's localStorage-based "remember me" behavior
 * so the mobile/desktop app doesn't force a fresh login every time the process is restarted.
 */
interface TokenStorage {
    fun save(session: PersistedSession)
    fun load(): PersistedSession?
    fun clear()
}

internal data class PkceRequest(val codeVerifier: String, val state: String, val codeChallenge: String)

private fun generatePkceRequest(): PkceRequest {
    val codeVerifier = secureRandomString(64)
    val state = secureRandomString(32)
    return PkceRequest(codeVerifier, state, sha256Base64Url(codeVerifier))
}

private fun buildAuthorizationUrl(
    config: KeycloakAuthConfig,
    redirectUri: String,
    pkce: PkceRequest
): String = URLBuilder(config.authUrl()).apply {
    parameters.append("response_type", "code")
    parameters.append("client_id", config.clientId)
    parameters.append("redirect_uri", redirectUri)
    parameters.append("scope", "openid profile email")
    parameters.append("state", pkce.state)
    parameters.append("code_challenge", pkce.codeChallenge)
    parameters.append("code_challenge_method", "S256")
}.buildString()

/**
 * Drives the OIDC Authorization Code + PKCE flow against Keycloak.
 *
 * The actual authorization request is opened by [launchAuthorizationUrl] (a system browser on
 * desktop, a custom-tab/browser Intent on Android), since a native app cannot POST credentials
 * directly per OIDC best practice. The platform is responsible for capturing the redirect back
 * to [redirectUri] and forwarding it to [handleAuthorizationResponse].
 */
class KeycloakAuthProvider(
    private val redirectUri: String,
    private val tokenStorage: TokenStorage,
    private val config: KeycloakAuthConfig = DefaultKeycloakConfig,
    private val launchAuthorizationUrl: (String) -> Unit
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val httpClient: HttpClient by lazy { createHttpClient() }

    private var pendingVerifier: String? = null
    private var pendingState: String? = null
    private var refreshJob: Job? = null

    init {
        val session = tokenStorage.load()
        if (session != null) {
            _authState.value = AuthState.Authenticated(
                username = session.username,
                accessToken = session.accessToken,
                roles = session.roles.toSet()
            ) as AuthState
            scheduleAutoRefresh()
        }
    }

    /**
     * Keeps the access token alive for as long as this session (process) stays open and the
     * refresh token remains valid, since Keycloak access tokens are short-lived (commonly ~5
     * minutes). Runs immediately if the persisted token is already expired (e.g. the app was
     * closed for longer than the access-token lifetime).
     */
    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                val expiresAt = tokenStorage.load()?.expiresAt
                val delayMs = if (expiresAt != null) {
                    (expiresAt - currentTimeMillis() - REFRESH_MARGIN_MS).coerceAtLeast(0L)
                } else {
                    0L
                }
                delay(delayMs)
                if (!refreshAccessToken()) {
                    handleRefreshFailure()
                    break
                }
            }
        }
    }

    /** Refresh token expired or was revoked: the persisted session can now never become valid
     * again on its own, so clear it and prompt the user to sign in again instead of leaving every
     * subsequent API call to silently fail with 401. */
    private fun handleRefreshFailure() {
        tokenStorage.clear()
        _authState.value = AuthState.Error("セッションの有効期限が切れました。再度ログインしてください。")
    }

    /** Exchanges the persisted refresh token for a new access token. Returns false if that fails
     * (refresh token expired/revoked). */
    private suspend fun refreshAccessToken(): Boolean {
        val session = tokenStorage.load() ?: return false
        val refreshToken = session.refreshToken ?: return false
        return try {
            val tokenResponse = httpClient.submitForm(
                url = config.tokenUrl(),
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", config.clientId)
                    append("refresh_token", refreshToken)
                }
            ).body<TokenResponse>()

            // リフレッシュ成功時に userinfo からロールを最新化する。
            // 取得に失敗してもトークン更新自体は有効なので、保存済みの値を維持する。
            val updatedRoles = try {
                httpClient.get(config.userInfoUrl()) {
                    bearerAuth(tokenResponse.accessToken)
                }.body<UserInfoResponse>().rbacRoles().sorted()
            } catch (e: Exception) {
                session.roles
            }

            tokenStorage.save(
                PersistedSession(
                    username = session.username,
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: refreshToken,
                    expiresAt = currentTimeMillis() + tokenResponse.expiresIn * 1000L,
                    roles = updatedRoles
                )
            )

            val current = _authState.value
            if (current is AuthState.Authenticated) {
                _authState.value = current.copy(accessToken = tokenResponse.accessToken, roles = updatedRoles.toSet()) as AuthState
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun login() {
        val pkce = generatePkceRequest()
        pendingVerifier = pkce.codeVerifier
        pendingState = pkce.state
        _authState.value = AuthState.Loading
        launchAuthorizationUrl(buildAuthorizationUrl(config, redirectUri, pkce))
    }

    /** Call once the platform has captured the redirect to [redirectUri]. */
    fun handleAuthorizationResponse(code: String?, state: String?, error: String? = null) {
        if (error != null) {
            _authState.value = AuthState.Error(error)
            return
        }
        val expectedState = pendingState
        val codeVerifier = pendingVerifier
        pendingState = null
        pendingVerifier = null

        if (code == null || state == null || state != expectedState || codeVerifier == null) {
            _authState.value = AuthState.Error("認証レスポンスが不正です")
            return
        }

        scope.launch {
            _authState.value = AuthState.Loading
            try {
                val tokenResponse = httpClient.submitForm(
                    url = config.tokenUrl(),
                    formParameters = parameters {
                        append("grant_type", "authorization_code")
                        append("client_id", config.clientId)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", codeVerifier)
                    }
                ).body<TokenResponse>()

                val userInfo = httpClient.get(config.userInfoUrl()) {
                    bearerAuth(tokenResponse.accessToken)
                }.body<UserInfoResponse>()

                val displayName = userInfo.name
                    ?: userInfo.preferredUsername
                    ?: userInfo.email
                    ?: "User"

                tokenStorage.save(
                    PersistedSession(
                        username = displayName,
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresAt = currentTimeMillis() + tokenResponse.expiresIn * 1000L,
                        roles = userInfo.rbacRoles().sorted()
                    )
                )

                val userRoles = userInfo.rbacRoles()
                _authState.value = AuthState.Authenticated(
                    username = displayName,
                    accessToken = tokenResponse.accessToken,
                    roles = userRoles
                ) as AuthState
                scheduleAutoRefresh()
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    message = e.message ?: "Authentication failed"
                )
            }
        }
    }

    fun logout() {
        refreshJob?.cancel()
        tokenStorage.clear()
        _authState.value = AuthState.Unauthenticated
    }

    fun close() {
        refreshJob?.cancel()
        scope.cancel()
        httpClient.close()
    }
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(
        val username: String,
        val accessToken: String,
        // admin-panelのclient roles(viewer/operator/admin、issue #183)。
        // 確認できない間は空(= 権限なし・安全側)。表示制御のみに使い、
        // 最終的な認可はサーバー側のRBAC(各routeのrequireRole)で行う。
        val roles: Set<String> = emptySet()
    ) {
        /** メニュー・ボタン・ページの表示制御用の権限(セキュリティ境界ではない)。 */
        val rbac: RbacPermissions get() = rbacPermissions(roles)

        /** adminロール保有者。旧isAdminの置き換え(rolesベース、issue #183)。 */
        val isAdmin: Boolean get() = rbac.isAdmin
    }

    data class Error(val message: String) : AuthState()
}