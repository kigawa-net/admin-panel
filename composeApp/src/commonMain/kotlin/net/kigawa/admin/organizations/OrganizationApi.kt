package net.kigawa.admin.organizations

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

object OrganizationApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchOrganizations(client: HttpClient, accessToken: String): OrganizationList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun createOrganization(client: HttpClient, accessToken: String, request: CreateOrganizationRequest): OrganizationActionResult {
    return client.post("${OrganizationApiConfig.baseUrl}/organizations") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()
}

suspend fun deleteOrganization(client: HttpClient, accessToken: String, orgId: String): OrganizationActionResult {
    return client.delete("${OrganizationApiConfig.baseUrl}/organizations/$orgId") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun fetchOrganizationMembers(client: HttpClient, accessToken: String, orgId: String): OrganizationMemberList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun addOrganizationMember(client: HttpClient, accessToken: String, orgId: String, userId: String): OrganizationActionResult {
    return client.post("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(AddOrganizationMemberRequest(userId))
    }.body()
}

suspend fun removeOrganizationMember(client: HttpClient, accessToken: String, orgId: String, userId: String): OrganizationActionResult {
    return client.delete("${OrganizationApiConfig.baseUrl}/organizations/$orgId/members/$userId") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun searchKigawaNetUsers(client: HttpClient, accessToken: String, query: String): KigawaNetUserList {
    return client.get("${OrganizationApiConfig.baseUrl}/organizations/users") {
        bearerAuth(accessToken)
        parameter("query", query)
    }.body()
}
