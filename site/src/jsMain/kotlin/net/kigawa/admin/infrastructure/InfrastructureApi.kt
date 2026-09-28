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

suspend fun fetchInfrastructureDetails(client: HttpClient, accessToken: String): InfrastructureDetails {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/details") {
        bearerAuth(accessToken)
    }.body()
}

/** リソース使用量グラフ用の時系列を取得する(issue #132)。 */
suspend fun fetchResourceUsage(client: HttpClient, accessToken: String, rangeMinutes: Int): ResourceUsageResponse {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/resource-usage?rangeMinutes=$rangeMinutes") {
        bearerAuth(accessToken)
    }.body()
}
