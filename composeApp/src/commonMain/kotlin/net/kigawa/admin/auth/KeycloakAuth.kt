package net.kigawa.admin.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

                _authState.value = AuthState.Authenticated(
                    username = displayName,
                    accessToken = tokenResponse.accessToken,
                    realm = realm
                )
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    message = e.message ?: "Authentication failed"
                )
            }
        }
    }

    fun logout() {
        _authState.value = AuthState.Unauthenticated
    }
}
