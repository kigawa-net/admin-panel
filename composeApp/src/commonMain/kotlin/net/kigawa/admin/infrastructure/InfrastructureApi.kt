package net.kigawa.admin.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get

object InfrastructureApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchInfrastructureTopology(client: HttpClient, accessToken: String): InfrastructureTopology {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure") {
        bearerAuth(accessToken)
    }.body()
}
