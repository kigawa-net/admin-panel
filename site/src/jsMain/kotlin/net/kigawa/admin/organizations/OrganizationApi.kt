package net.kigawa.admin.organizations

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object OrganizationApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

/**
 * サーバーが4xx/5xxを返したときに投げられる例外。メッセージにはサーバーが返した
 * 理由(サービスアカウント未設定など)が入るため、そのまま画面に表示できる。
 */
class OrganizationApiException(message: String) : Exception(message)

/** サーバー側のエラー応答本文(`mapOf("error" to ...)`)に対応する。 */
@Serializable
private data class ApiErrorDto(val error: String? = null)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * 2xx以外なら、サーバーが返した error メッセージを例外として投げる。
 *
 * このクライアントは `expectSuccess` を有効にしていない(Ktor 3の既定はfalse)ため、
 * Ktor本体は非2xxを例外にしてくれない。そのまま `.body()` を呼ぶと
 * 「Field 'organizations' is required」といったJSON変換エラーになり、原因が
 * 画面にもサーバーログにも残らない。ステータスと本文を明示的に扱う。
 */
private suspend fun HttpResponse.throwIfNotSuccess(): HttpResponse {
    if (status.value in 200..299) return this
    val serverMessage = runCatching {
        errorJson.decodeFromString<ApiErrorDto>(bodyAsText()).error
    }.getOrNull()?.takeIf { it.isNotBlank() }
    throw OrganizationApiException(
        serverMessage ?: "リクエストに失敗しました (HTTP ${status.value})"
    )
}

suspend fun fetchOrganizations(client: HttpClient, accessToken: String): OrganizationList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** 呼び出したユーザー自身が所属する組織のみを取得する(一般ユーザー向け)。 */
suspend fun fetchMyOrganizations(client: HttpClient, accessToken: String): OrganizationList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations/mine") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun createOrganization(client: HttpClient, accessToken: String, request: CreateOrganizationRequest): OrganizationActionResult {
    return client.post("${OrganizationApiConfig.baseUrl}/organizations") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.throwIfNotSuccess().body()
}

suspend fun deleteOrganization(client: HttpClient, accessToken: String, orgId: String): OrganizationActionResult {
    return client.delete("${OrganizationApiConfig.baseUrl}/organizations/$orgId") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun fetchOrganizationMembers(client: HttpClient, accessToken: String, orgId: String): OrganizationMemberList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun addOrganizationMember(client: HttpClient, accessToken: String, orgId: String, userId: String): OrganizationActionResult {
    return client.post("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(AddOrganizationMemberRequest(userId))
    }.throwIfNotSuccess().body()
}

suspend fun removeOrganizationMember(client: HttpClient, accessToken: String, orgId: String, userId: String): OrganizationActionResult {
    return client.delete("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members/$userId") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun searchKigawaNetUsers(client: HttpClient, accessToken: String, query: String): KigawaNetUserList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations/users") {
        bearerAuth(accessToken)
        parameter("query", query)
    }.throwIfNotSuccess().body()
}
