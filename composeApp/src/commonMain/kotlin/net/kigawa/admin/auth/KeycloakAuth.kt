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

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(val username: String, val accessToken: String) : AuthState()
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
    val realm: String
    val clientId: String
}

object DefaultKeycloakConfig : KeycloakAuthConfig {
    override val serverUrl: String = "https://user.kigawa.net"
    override val realm: String = "manage"
    override val clientId: String = "admin-panel"
}

private val KeycloakAuthConfig.authUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/auth"
private val KeycloakAuthConfig.tokenUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/token"
private val KeycloakAuthConfig.userInfoUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/userinfo"

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
    redirectUri: String,
    pkce: PkceRequest
): String = URLBuilder(config.authUrl).apply {
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
                    url = config.tokenUrl,
                    formParameters = parameters {
                        append("grant_type", "authorization_code")
                        append("client_id", config.clientId)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", codeVerifier)
                    }
                ).body<TokenResponse>()

                val userInfo = httpClient.get(config.userInfoUrl) {
                    bearerAuth(tokenResponse.accessToken)
                }.body<UserInfoResponse>()

                val displayName = userInfo.name
                    ?: userInfo.preferredUsername
                    ?: userInfo.email
                    ?: "User"

                _authState.value = AuthState.Authenticated(
                    username = displayName,
                    accessToken = tokenResponse.accessToken
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
