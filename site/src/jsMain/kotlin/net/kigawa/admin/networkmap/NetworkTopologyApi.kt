package net.kigawa.admin.networkmap

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get

object NetworkTopologyApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchNetworkTopology(client: HttpClient, accessToken: String): NetworkTopology {
    return client.get("${NetworkTopologyApiConfig.baseUrl}/network-topology") {
        bearerAuth(accessToken)
    }.body()
}
