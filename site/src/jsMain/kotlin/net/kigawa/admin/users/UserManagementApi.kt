package net.kigawa.admin.users

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

object UserManagementApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchUsers(client: HttpClient, accessToken: String): KeycloakUserList {
    return client.get("${UserManagementApiConfig.baseUrl}/users") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun createUser(client: HttpClient, accessToken: String, request: CreateUserRequest): UserActionResult {
    return client.post("${UserManagementApiConfig.baseUrl}/users") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()
}

suspend fun deleteUser(client: HttpClient, accessToken: String, userId: String): UserActionResult {
    return client.delete("${UserManagementApiConfig.baseUrl}/users/$userId") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun enableUser(client: HttpClient, accessToken: String, userId: String): UserActionResult {
    return client.post("${UserManagementApiConfig.baseUrl}/users/$userId/enable") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun disableUser(client: HttpClient, accessToken: String, userId: String): UserActionResult {
    return client.post("${UserManagementApiConfig.baseUrl}/users/$userId/disable") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun resetUserPassword(client: HttpClient, accessToken: String, userId: String, request: ResetPasswordRequest): UserActionResult {
    return client.post("${UserManagementApiConfig.baseUrl}/users/$userId/reset-password") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()
}
