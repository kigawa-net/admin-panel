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

/**
 * 管理用realm(全機能。サーバー管理の閲覧・操作を含む)とpublic用realm(閲覧専用。
 * ダッシュボード・ネットワークマップ・トラフィックのみ)の2つを切り替えてログインできる。
 * どちらのrealmで認証したかはバックエンド側でも独立に検証される(クライアント側の画面出し
 * 分けは利便性のためであり、アクセス制御の境界はサーバー側にある)。
 */
enum class KeycloakRealm(val realmName: String, val label: String) {
    ADMIN("manage", "管理者"),
    PUBLIC("kigawa-net", "一般利用者")
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(val username: String, val accessToken: String, val realm: KeycloakRealm) : AuthState()
    data class Error(val message: String) : AuthState()
}

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
    @SerialName("name") val name: String? = null
)

interface KeycloakAuthConfig {
    val serverUrl: String
    val clientId: String
}

object DefaultKeycloakConfig : KeycloakAuthConfig {
    override val serverUrl: String = "https://user.kigawa.net"
    override val clientId: String = "admin-panel"
}

private fun KeycloakAuthConfig.authUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/auth"
private fun KeycloakAuthConfig.tokenUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/token"
private fun KeycloakAuthConfig.userInfoUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/userinfo"

expect fun createHttpClient(): HttpClient

/** Generates a cryptographically secure random string usable as a PKCE code verifier / OAuth state. */
expect fun secureRandomString(length: Int): String

/** SHA-256 hashes [input] and returns the result as Base64url (no padding), per RFC 7636. */
expect fun sha256Base64Url(input: String): String

expect fun currentTimeMillis(): Long

/** Refresh this long before actual expiry, to allow for request latency and clock skew. */
private const val REFRESH_MARGIN_MS = 30_000L

data class PersistedSession(
    val realm: KeycloakRealm,
    val username: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long
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
    realm: KeycloakRealm,
    redirectUri: String,
    pkce: PkceRequest
): String = URLBuilder(config.authUrl(realm)).apply {
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
    private var pendingRealm: KeycloakRealm? = null
    private var refreshJob: Job? = null

    init {
        val session = tokenStorage.load()
        if (session != null) {
            _authState.value = AuthState.Authenticated(
                username = session.username,
                accessToken = session.accessToken,
                realm = session.realm
            )
            scheduleAutoRefresh(session.realm)
        }
    }

    /**
     * Keeps the access token alive for as long as this session (process) stays open and the
     * refresh token remains valid, since Keycloak access tokens are short-lived (commonly ~5
     * minutes). Runs immediately if the persisted token is already expired (e.g. the app was
     * closed for longer than the access-token lifetime).
     */
    private fun scheduleAutoRefresh(realm: KeycloakRealm) {
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
                if (!refreshAccessToken(realm)) {
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
    private suspend fun refreshAccessToken(realm: KeycloakRealm): Boolean {
        val session = tokenStorage.load() ?: return false
        val refreshToken = session.refreshToken ?: return false
        return try {
            val tokenResponse = httpClient.submitForm(
                url = config.tokenUrl(realm),
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", config.clientId)
                    append("refresh_token", refreshToken)
                }
            ).body<TokenResponse>()

            tokenStorage.save(
                PersistedSession(
                    realm = realm,
                    username = session.username,
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: refreshToken,
                    expiresAt = currentTimeMillis() + tokenResponse.expiresIn * 1000L
                )
            )

            val current = _authState.value
            if (current is AuthState.Authenticated) {
                _authState.value = current.copy(accessToken = tokenResponse.accessToken)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun login(realm: KeycloakRealm) {
        val pkce = generatePkceRequest()
        pendingVerifier = pkce.codeVerifier
        pendingState = pkce.state
        pendingRealm = realm
        _authState.value = AuthState.Loading
        launchAuthorizationUrl(buildAuthorizationUrl(config, realm, redirectUri, pkce))
    }

    /** Call once the platform has captured the redirect to [redirectUri]. */
    fun handleAuthorizationResponse(code: String?, state: String?, error: String? = null) {
        if (error != null) {
            _authState.value = AuthState.Error(error)
            return
        }
        val expectedState = pendingState
        val codeVerifier = pendingVerifier
        val realm = pendingRealm
        pendingState = null
        pendingVerifier = null
        pendingRealm = null

        if (code == null || state == null || state != expectedState || codeVerifier == null || realm == null) {
            _authState.value = AuthState.Error("認証レスポンスが不正です")
            return
        }

        scope.launch {
            _authState.value = AuthState.Loading
            try {
                val tokenResponse = httpClient.submitForm(
                    url = config.tokenUrl(realm),
                    formParameters = parameters {
                        append("grant_type", "authorization_code")
                        append("client_id", config.clientId)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", codeVerifier)
                    }
                ).body<TokenResponse>()

                val userInfo = httpClient.get(config.userInfoUrl(realm)) {
                    bearerAuth(tokenResponse.accessToken)
                }.body<UserInfoResponse>()

                val displayName = userInfo.name
                    ?: userInfo.preferredUsername
                    ?: userInfo.email
                    ?: "User"

                tokenStorage.save(
                    PersistedSession(
                        realm = realm,
                        username = displayName,
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresAt = currentTimeMillis() + tokenResponse.expiresIn * 1000L
                    )
                )

                _authState.value = AuthState.Authenticated(
                    username = displayName,
                    accessToken = tokenResponse.accessToken,
                    realm = realm
                )
                scheduleAutoRefresh(realm)
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
