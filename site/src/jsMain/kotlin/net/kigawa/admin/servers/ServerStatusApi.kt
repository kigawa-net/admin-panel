package net.kigawa.admin.servers

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get

object ServerStatusApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchServerStatuses(client: HttpClient, accessToken: String): ServerStatusList {
    return client.get("${ServerStatusApiConfig.baseUrl}/servers") {
        bearerAuth(accessToken)
    }.body()
}
