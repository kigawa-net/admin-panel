package net.kigawa.admin.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.browser.localStorage
import kotlinx.browser.window
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
import kotlinx.serialization.json.Json
import kotlinx.coroutines.await
import net.kigawa.admin.util.URLSearchParams
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.js.Promise

/**
 * 管理用realm(全機能。サーバー管理の閲覧・操作を含む)とpublic用realm(閲覧専用。
 * ダッシュボード・ネットワークマップ・トラフィックのみ)の2つを切り替えてログインできる。
 * どちらのrealmで認証したかはバックエンド側でも独立に検証される。
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
    @SerialName("scope") val scope: String? = null,
    @SerialName("id_token") val idToken: String? = null
)

@Serializable
data class UserInfoResponse(
    @SerialName("sub") val sub: String,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("name") val name: String? = null
)

object KeycloakConfig {
    val serverUrl: String = js("window.__KEYCLOAK_URL__ || 'https://user.kigawa.net'") as String
    val clientId: String = js("window.__KEYCLOAK_CLIENT_ID__ || 'admin-panel'") as String

    fun authUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/auth"
    fun tokenUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/token"
    fun userInfoUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/userinfo"
    fun logoutUrl(realm: KeycloakRealm) = "$serverUrl/realms/${realm.realmName}/protocol/openid-connect/logout"
}

private const val KEY_CODE_VERIFIER = "kc_code_verifier"
private const val KEY_STATE = "kc_state"
private const val KEY_REALM = "kc_realm"
private const val KEY_ACCESS_TOKEN = "kc_access_token"
private const val KEY_ID_TOKEN = "kc_id_token"
private const val KEY_REFRESH_TOKEN = "kc_refresh_token"
private const val KEY_EXPIRES_AT = "kc_expires_at"
private const val KEY_USERNAME = "kc_username"

/** Refresh this long before actual expiry, to allow for request latency and clock skew. */
private const val REFRESH_MARGIN_MS = 30_000L

private fun nowMillis(): Long = (js("Date.now()") as Double).toLong()

private fun generateRandom(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val array = js("new Uint8Array(length)")
    js("crypto.getRandomValues(array)")
    val sb = StringBuilder(length)
    repeat(length) { i ->
        sb.append(chars[(array[i] as Int) % chars.length])
    }
    return sb.toString()
}

private suspend fun sha256Base64Url(input: String): String {
    val encoder: dynamic = js("new TextEncoder()")
    val data: dynamic = encoder.encode(input)
    @Suppress("UNCHECKED_CAST")
    val hashBuffer = (js("crypto.subtle.digest('SHA-256', data)") as Promise<dynamic>).await()
    val hashArray: dynamic = js("Array.from(new Uint8Array(hashBuffer))")
    val base64 = js("btoa(String.fromCharCode.apply(null, hashArray))") as String
    return base64
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')
}

class KeycloakAuthProvider : AutoCloseable {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val httpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var refreshJob: Job? = null

    fun setError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    fun init() {
        val token = localStorage[KEY_ACCESS_TOKEN]
        val username = localStorage[KEY_USERNAME]
        val realm = localStorage[KEY_REALM]?.let { name -> KeycloakRealm.entries.find { it.realmName == name } }
        if (token != null && username != null && realm != null) {
            _authState.value = AuthState.Authenticated(username = username, accessToken = token, realm = realm)
            scheduleAutoRefresh(realm)
        }
    }

    /**
     * Keeps the access token alive for as long as this page stays open and the refresh token
     * remains valid, since Keycloak access tokens are short-lived (commonly ~5 minutes) and this
     * app has no other mechanism to renew them. Each page navigation creates a fresh provider
     * (and thus a fresh loop), so this only needs to cover a single page's lifetime.
     */
    private fun scheduleAutoRefresh(realm: KeycloakRealm) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                val expiresAt = localStorage[KEY_EXPIRES_AT]?.toLongOrNull()
                val delayMs = if (expiresAt != null) {
                    (expiresAt - nowMillis() - REFRESH_MARGIN_MS).coerceAtLeast(0L)
                } else {
                    0L
                }
                delay(delayMs)
                if (!refreshAccessToken(realm)) break
            }
        }
    }

    /** Exchanges the stored refresh token for a new access token. Returns false if that fails
     * (refresh token expired/revoked), leaving the current, likely-now-stale access token in
     * place until the user next has to log in again. */
    private suspend fun refreshAccessToken(realm: KeycloakRealm): Boolean {
        val refreshToken = localStorage[KEY_REFRESH_TOKEN] ?: return false
        return try {
            val tokenResponse = httpClient.submitForm(
                url = KeycloakConfig.tokenUrl(realm),
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", KeycloakConfig.clientId)
                    append("refresh_token", refreshToken)
                }
            ).body<TokenResponse>()

            persistTokens(tokenResponse)

            val current = _authState.value
            if (current is AuthState.Authenticated) {
                _authState.value = current.copy(accessToken = tokenResponse.accessToken)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun persistTokens(tokenResponse: TokenResponse) {
        localStorage[KEY_ACCESS_TOKEN] = tokenResponse.accessToken
        localStorage[KEY_EXPIRES_AT] = (nowMillis() + tokenResponse.expiresIn * 1000L).toString()
        if (tokenResponse.refreshToken != null) {
            localStorage[KEY_REFRESH_TOKEN] = tokenResponse.refreshToken
        }
        if (tokenResponse.idToken != null) {
            localStorage[KEY_ID_TOKEN] = tokenResponse.idToken
        } else {
            localStorage.removeItem(KEY_ID_TOKEN)
        }
    }

    suspend fun startLogin(realm: KeycloakRealm) {
        val codeVerifier = generateRandom(128)
        val state = generateRandom(32)
        val codeChallenge = sha256Base64Url(codeVerifier)

        localStorage[KEY_CODE_VERIFIER] = codeVerifier
        localStorage[KEY_STATE] = state
        localStorage[KEY_REALM] = realm.realmName

        val redirectUri = "${window.location.origin}/callback"
        val params = URLSearchParams()
        params.set("response_type", "code")
        params.set("client_id", KeycloakConfig.clientId)
        params.set("redirect_uri", redirectUri)
        params.set("scope", "openid profile email")
        params.set("state", state)
        params.set("code_challenge", codeChallenge)
        params.set("code_challenge_method", "S256")

        window.location.href = "${KeycloakConfig.authUrl(realm)}?$params"
    }

    suspend fun handleCallback(code: String, state: String) {
        _authState.value = AuthState.Loading

        val savedState = localStorage[KEY_STATE]
        if (state != savedState) {
            _authState.value = AuthState.Error("Invalid state parameter")
            return
        }

        val codeVerifier = localStorage[KEY_CODE_VERIFIER]
        val realm = localStorage[KEY_REALM]?.let { name -> KeycloakRealm.entries.find { it.realmName == name } }
        if (codeVerifier == null || realm == null) {
            _authState.value = AuthState.Error("Missing code verifier")
            return
        }

        localStorage.removeItem(KEY_CODE_VERIFIER)
        localStorage.removeItem(KEY_STATE)

        try {
            val redirectUri = "${window.location.origin}/callback"
            val tokenResponse = httpClient.submitForm(
                url = KeycloakConfig.tokenUrl(realm),
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("client_id", KeycloakConfig.clientId)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                }
            ).body<TokenResponse>()

            val userInfo = httpClient.get(KeycloakConfig.userInfoUrl(realm)) {
                bearerAuth(tokenResponse.accessToken)
            }.body<UserInfoResponse>()

            val displayName = userInfo.name
                ?: userInfo.preferredUsername
                ?: userInfo.email
                ?: "User"

            persistTokens(tokenResponse)
            localStorage[KEY_USERNAME] = displayName

            _authState.value = AuthState.Authenticated(
                username = displayName,
                accessToken = tokenResponse.accessToken,
                realm = realm
            )

            window.location.href = "/"
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Authentication failed")
        }
    }

    fun logout() {
        val idToken = localStorage[KEY_ID_TOKEN]
        val realm = localStorage[KEY_REALM]?.let { name -> KeycloakRealm.entries.find { it.realmName == name } }
            ?: KeycloakRealm.ADMIN
        refreshJob?.cancel()
        localStorage.removeItem(KEY_ACCESS_TOKEN)
        localStorage.removeItem(KEY_ID_TOKEN)
        localStorage.removeItem(KEY_REFRESH_TOKEN)
        localStorage.removeItem(KEY_EXPIRES_AT)
        localStorage.removeItem(KEY_USERNAME)
        localStorage.removeItem(KEY_REALM)
        _authState.value = AuthState.Unauthenticated

        val params = URLSearchParams()
        params.set("client_id", KeycloakConfig.clientId)
        params.set("post_logout_redirect_uri", window.location.origin)
        if (idToken != null) params.set("id_token_hint", idToken)

        window.location.href = "${KeycloakConfig.logoutUrl(realm)}?$params"
    }

    override fun close() {
        refreshJob?.cancel()
        scope.cancel()
        httpClient.close()
    }
}
