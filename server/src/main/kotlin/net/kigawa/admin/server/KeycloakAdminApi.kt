package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val ADMIN_REALM = "manage"

private val keycloakServerUrl = System.getenv("KEYCLOAK_SERVER_URL") ?: "https://user.kigawa.net"

/**
 * ユーザー管理機能はKeycloak Admin REST APIを叩く専用のサービスアカウント(client_credentials
 * グラント)を使う。既存のOIDCログイン用クライアント(admin-panel)とは別に、manage realm側で
 * サービスアカウント有効なconfidential clientを作成し、realm-managementクライアントの
 * manage-usersロールを付与しておく必要がある(Keycloak側の設定はこのリポジトリの範囲外)。
 * クライアントIDやシークレットが未設定/無効な間は、全操作が failure として返る。
 */
private val adminApiClientId = System.getenv("KEYCLOAK_ADMIN_API_CLIENT_ID")
private val adminApiClientSecret = System.getenv("KEYCLOAK_ADMIN_API_CLIENT_SECRET")

@Serializable
private data class ServiceAccountTokenResponse(
    @SerialName("access_token") val accessToken: String
)

@Serializable
data class KeycloakUserDto(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class KeycloakUserListDto(val users: List<KeycloakUserDto>)

@Serializable
data class CreateUserRequest(
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val temporaryPassword: String
)

@Serializable
data class ResetPasswordRequest(
    val newPassword: String,
    val temporary: Boolean = true
)

private suspend fun serviceAccountToken(client: HttpClient): String? {
    val clientId = adminApiClientId
    val clientSecret = adminApiClientSecret
    if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return null

    return try {
        val response = client.submitForm(
            url = "$keycloakServerUrl/realms/$ADMIN_REALM/protocol/openid-connect/token",
            formParameters = parameters {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
            }
        ).body<ServiceAccountTokenResponse>()
        response.accessToken
    } catch (e: Exception) {
        null
    }
}

suspend fun listKeycloakUsers(client: HttpClient): KeycloakUserListDto? {
    val token = serviceAccountToken(client) ?: return null
    return try {
        val users = client.get("$keycloakServerUrl/admin/realms/$ADMIN_REALM/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<KeycloakUserDto>>()
        KeycloakUserListDto(users)
    } catch (e: Exception) {
        null
    }
}

suspend fun setKeycloakUserEnabled(client: HttpClient, userId: String, enabled: Boolean): ActionResultDto {
    val token = serviceAccountToken(client)
        ?: return ActionResultDto(false, "Keycloak管理APIのサービスアカウントが未設定です")
    return try {
        val response: HttpResponse = client.put("$keycloakServerUrl/admin/realms/$ADMIN_REALM/users/$userId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":$enabled}""")
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, if (enabled) "有効化しました" else "無効化しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun resetKeycloakUserPassword(client: HttpClient, userId: String, newPassword: String, temporary: Boolean): ActionResultDto {
    val token = serviceAccountToken(client)
        ?: return ActionResultDto(false, "Keycloak管理APIのサービスアカウントが未設定です")
    return try {
        val response: HttpResponse = client.put("$keycloakServerUrl/admin/realms/$ADMIN_REALM/users/$userId/reset-password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"password","value":${jsonStringLiteral(newPassword)},"temporary":$temporary}""")
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "パスワードをリセットしました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun createKeycloakUser(client: HttpClient, request: CreateUserRequest): ActionResultDto {
    val token = serviceAccountToken(client)
        ?: return ActionResultDto(false, "Keycloak管理APIのサービスアカウントが未設定です")
    return try {
        val body = buildString {
            append("{")
            append("\"username\":${jsonStringLiteral(request.username)},")
            append("\"enabled\":true,")
            if (!request.email.isNullOrBlank()) append("\"email\":${jsonStringLiteral(request.email)},")
            if (!request.firstName.isNullOrBlank()) append("\"firstName\":${jsonStringLiteral(request.firstName)},")
            if (!request.lastName.isNullOrBlank()) append("\"lastName\":${jsonStringLiteral(request.lastName)},")
            append("\"credentials\":[{\"type\":\"password\",\"value\":${jsonStringLiteral(request.temporaryPassword)},\"temporary\":true}]")
            append("}")
        }
        val response: HttpResponse = client.post("$keycloakServerUrl/admin/realms/$ADMIN_REALM/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "ユーザーを作成しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun deleteKeycloakUser(client: HttpClient, userId: String): ActionResultDto {
    val token = serviceAccountToken(client)
        ?: return ActionResultDto(false, "Keycloak管理APIのサービスアカウントが未設定です")
    return try {
        val response: HttpResponse = client.delete("$keycloakServerUrl/admin/realms/$ADMIN_REALM/users/$userId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "ユーザーを削除しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

/** JSON文字列リテラルとして安全にエスケープする(バックスラッシュ・二重引用符・制御文字)。 */
private fun jsonStringLiteral(value: String): String {
    val escaped = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
    return escaped
}
