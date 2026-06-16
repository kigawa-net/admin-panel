package net.kigawa.admin.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

object DefaultKeycloakConfig {
    val serverUrl: String = "http://localhost:8080"
    val realm: String = "master"
    val clientId: String = "admin-panel"
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

    private val tokenUrl =
        "${DefaultKeycloakConfig.serverUrl}/realms/${DefaultKeycloakConfig.realm}/protocol/openid-connect/token"

    private val userInfoUrl =
        "${DefaultKeycloakConfig.serverUrl}/realms/${DefaultKeycloakConfig.realm}/protocol/openid-connect/userinfo"

    suspend fun login(username: String, password: String) {
        _authState.value = AuthState.Loading
        try {
            val tokenResponse = httpClient.submitForm(
                url = tokenUrl,
                formParameters = parameters {
                    append("grant_type", "password")
                    append("client_id", DefaultKeycloakConfig.clientId)
                    append("username", username)
                    append("password", password)
                }
            ).body<TokenResponse>()

            val userInfo = httpClient.get(userInfoUrl) {
                bearerAuth(tokenResponse.accessToken)
            }.body<UserInfoResponse>()

            val displayName = userInfo.name
                ?: userInfo.preferredUsername
                ?: userInfo.email
                ?: username

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

    fun logout() {
        _authState.value = AuthState.Unauthenticated
    }

    override fun close() {
        httpClient.close()
    }
}
