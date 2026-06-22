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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.await
import net.kigawa.admin.util.URLSearchParams
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.js.Promise

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

object KeycloakConfig {
    val serverUrl: String = js("window.__KEYCLOAK_URL__ || 'https://user.kigawa.net'") as String
    val realm: String = js("window.__KEYCLOAK_REALM__ || 'manage'") as String
    val clientId: String = js("window.__KEYCLOAK_CLIENT_ID__ || 'admin-panel'") as String

    val authUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/auth"
    val tokenUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/token"
    val userInfoUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/userinfo"
    val logoutUrl get() = "$serverUrl/realms/$realm/protocol/openid-connect/logout"
}

private const val KEY_CODE_VERIFIER = "kc_code_verifier"
private const val KEY_STATE = "kc_state"
private const val KEY_ACCESS_TOKEN = "kc_access_token"
private const val KEY_USERNAME = "kc_username"

private fun generateRandom(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val array = js("new Uint8Array(length)") as dynamic
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

    fun init() {
        val token = localStorage[KEY_ACCESS_TOKEN]
        val username = localStorage[KEY_USERNAME]
        if (token != null && username != null) {
            _authState.value = AuthState.Authenticated(username = username, accessToken = token)
        }
    }

    suspend fun startLogin() {
        val codeVerifier = generateRandom(128)
        val state = generateRandom(32)
        val codeChallenge = sha256Base64Url(codeVerifier)

        localStorage[KEY_CODE_VERIFIER] = codeVerifier
        localStorage[KEY_STATE] = state

        val redirectUri = "${window.location.origin}/callback"
        val params = URLSearchParams()
        params.set("response_type", "code")
        params.set("client_id", KeycloakConfig.clientId)
        params.set("redirect_uri", redirectUri)
        params.set("scope", "openid profile email")
        params.set("state", state)
        params.set("code_challenge", codeChallenge)
        params.set("code_challenge_method", "S256")

        window.location.href = "${KeycloakConfig.authUrl}?$params"
    }

    suspend fun handleCallback(code: String, state: String) {
        _authState.value = AuthState.Loading

        val savedState = localStorage[KEY_STATE]
        if (state != savedState) {
            _authState.value = AuthState.Error("Invalid state parameter")
            return
        }

        val codeVerifier = localStorage[KEY_CODE_VERIFIER]
        if (codeVerifier == null) {
            _authState.value = AuthState.Error("Missing code verifier")
            return
        }

        localStorage.removeItem(KEY_CODE_VERIFIER)
        localStorage.removeItem(KEY_STATE)

        try {
            val redirectUri = "${window.location.origin}/callback"
            val tokenResponse = httpClient.submitForm(
                url = KeycloakConfig.tokenUrl,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("client_id", KeycloakConfig.clientId)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                }
            ).body<TokenResponse>()

            val userInfo = httpClient.get(KeycloakConfig.userInfoUrl) {
                bearerAuth(tokenResponse.accessToken)
            }.body<UserInfoResponse>()

            val displayName = userInfo.name
                ?: userInfo.preferredUsername
                ?: userInfo.email
                ?: "User"

            localStorage[KEY_ACCESS_TOKEN] = tokenResponse.accessToken
            localStorage[KEY_USERNAME] = displayName

            _authState.value = AuthState.Authenticated(
                username = displayName,
                accessToken = tokenResponse.accessToken
            )

            window.location.href = "/"
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Authentication failed")
        }
    }

    fun logout() {
        val token = localStorage[KEY_ACCESS_TOKEN]
        localStorage.removeItem(KEY_ACCESS_TOKEN)
        localStorage.removeItem(KEY_USERNAME)
        _authState.value = AuthState.Unauthenticated

        val params = URLSearchParams()
        params.set("client_id", KeycloakConfig.clientId)
        params.set("post_logout_redirect_uri", window.location.origin)
        if (token != null) params.set("id_token_hint", token)

        window.location.href = "${KeycloakConfig.logoutUrl}?$params"
    }

    override fun close() {
        httpClient.close()
    }
}

