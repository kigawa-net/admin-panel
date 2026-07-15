package net.kigawa.admin.traffic

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter

object TrafficApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchTraffic(client: HttpClient, accessToken: String, rangeMinutes: Int = 60): TrafficResponse {
    return client.get("${TrafficApiConfig.baseUrl}/traffic") {
        bearerAuth(accessToken)
        parameter("rangeMinutes", rangeMinutes)
    }.body()
}
