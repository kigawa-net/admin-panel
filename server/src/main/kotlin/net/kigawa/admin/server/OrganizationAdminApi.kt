package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val ORG_REALM = "kigawa-net"

/**
 * 組織管理機能もユーザー管理機能([KeycloakAdminApi])と同様、専用のサービスアカウント
 * (client_credentials グラント)経由でKeycloak Admin REST APIを叩く。Organizationsは
 * kigawa-net realm(一般利用者向け)で有効化されているため、manage realm用のサービス
 * アカウントとは別のクライアントを使う(Keycloak側の設定はこのリポジトリの範囲外)。
 */
private val orgApiClientId = System.getenv("KEYCLOAK_ORG_API_CLIENT_ID")
private val orgApiClientSecret = System.getenv("KEYCLOAK_ORG_API_CLIENT_SECRET")

@Serializable
private data class OrgServiceAccountTokenResponse(
    @SerialName("access_token") val accessToken: String
)

@Serializable
data class OrganizationDomainDto(val name: String, val verified: Boolean = false)

@Serializable
data class OrganizationDto(
    val id: String,
    val name: String,
    val alias: String? = null,
    val enabled: Boolean = true,
    val description: String? = null,
    val domains: List<OrganizationDomainDto> = emptyList()
)

@Serializable
data class OrganizationListDto(val organizations: List<OrganizationDto>)

@Serializable
data class CreateOrganizationRequest(
    val name: String,
    val domain: String,
    val description: String? = null
)

@Serializable
data class OrganizationMemberDto(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)

@Serializable
data class OrganizationMemberListDto(val members: List<OrganizationMemberDto>)

@Serializable
data class AddOrganizationMemberRequest(val userId: String)

@Serializable
data class KigawaNetUserListDto(val users: List<OrganizationMemberDto>)

private suspend fun orgServiceAccountToken(client: HttpClient): String? {
    val clientId = orgApiClientId
    val clientSecret = orgApiClientSecret
    if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return null

    return try {
        val response = client.submitForm(
            url = "$keycloakServerUrl/realms/$ORG_REALM/protocol/openid-connect/token",
            formParameters = parameters {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
            }
        ).body<OrgServiceAccountTokenResponse>()
        response.accessToken
    } catch (e: Exception) {
        null
    }
}

suspend fun listOrganizations(client: HttpClient): OrganizationListDto? {
    val token = orgServiceAccountToken(client) ?: return null
    return try {
        val orgs = client.get("$keycloakServerUrl/admin/realms/$ORG_REALM/organizations") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<OrganizationDto>>()
        OrganizationListDto(orgs)
    } catch (e: Exception) {
        null
    }
}

suspend fun createOrganization(client: HttpClient, request: CreateOrganizationRequest): ActionResultDto {
    val token = orgServiceAccountToken(client)
        ?: return ActionResultDto(false, "組織管理APIのサービスアカウントが未設定です")
    return try {
        val body = buildString {
            append("{")
            append("\"name\":${jsonStringLiteral(request.name)},")
            append("\"enabled\":true,")
            if (!request.description.isNullOrBlank()) {
                append("\"description\":${jsonStringLiteral(request.description)},")
            }
            append("\"domains\":[{\"name\":${jsonStringLiteral(request.domain)},\"verified\":false}]")
            append("}")
        }
        val response: HttpResponse = client.post("$keycloakServerUrl/admin/realms/$ORG_REALM/organizations") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "組織を作成しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun deleteOrganization(client: HttpClient, orgId: String): ActionResultDto {
    val token = orgServiceAccountToken(client)
        ?: return ActionResultDto(false, "組織管理APIのサービスアカウントが未設定です")
    return try {
        val response: HttpResponse = client.delete("$keycloakServerUrl/admin/realms/$ORG_REALM/organizations/$orgId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "組織を削除しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun listOrganizationMembers(client: HttpClient, orgId: String): OrganizationMemberListDto? {
    val token = orgServiceAccountToken(client) ?: return null
    return try {
        val members = client.get("$keycloakServerUrl/admin/realms/$ORG_REALM/organizations/$orgId/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<OrganizationMemberDto>>()
        OrganizationMemberListDto(members)
    } catch (e: Exception) {
        null
    }
}

suspend fun addOrganizationMember(client: HttpClient, orgId: String, userId: String): ActionResultDto {
    val token = orgServiceAccountToken(client)
        ?: return ActionResultDto(false, "組織管理APIのサービスアカウントが未設定です")
    return try {
        // Keycloak Admin REST APIのこのエンドポイントはJSONではなくtext/plainでユーザーIDを渡す。
        val response: HttpResponse = client.post("$keycloakServerUrl/admin/realms/$ORG_REALM/organizations/$orgId/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Text.Plain)
            setBody(userId)
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "メンバーを追加しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

suspend fun removeOrganizationMember(client: HttpClient, orgId: String, userId: String): ActionResultDto {
    val token = orgServiceAccountToken(client)
        ?: return ActionResultDto(false, "組織管理APIのサービスアカウントが未設定です")
    return try {
        val response: HttpResponse = client.delete(
            "$keycloakServerUrl/admin/realms/$ORG_REALM/organizations/$orgId/members/$userId"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.value in 200..299) {
            ActionResultDto(true, "メンバーを削除しました")
        } else {
            ActionResultDto(false, "失敗しました (HTTP ${response.status.value})")
        }
    } catch (e: Exception) {
        ActionResultDto(false, e.message ?: "失敗しました")
    }
}

/** メンバー追加時のユーザー検索用(kigawa-net realmの一般ユーザーを対象)。 */
suspend fun searchKigawaNetUsers(client: HttpClient, query: String): KigawaNetUserListDto? {
    val token = orgServiceAccountToken(client) ?: return null
    return try {
        val users = client.get("$keycloakServerUrl/admin/realms/$ORG_REALM/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            url {
                parameters.append("search", query)
                parameters.append("max", "20")
            }
        }.body<List<OrganizationMemberDto>>()
        KigawaNetUserListDto(users)
    } catch (e: Exception) {
        null
    }
}
