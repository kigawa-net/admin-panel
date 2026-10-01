package net.kigawa.admin.watchdog

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WatchdogApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

@Serializable
data class WatchdogStatus(
    @SerialName("configured") val configured: Boolean,
    @SerialName("secondsSinceLastPing") val secondsSinceLastPing: Long?,
    @SerialName("staleAfterSeconds") val staleAfterSeconds: Long,
    @SerialName("isStale") val isStale: Boolean
)

suspend fun fetchWatchdogStatus(client: HttpClient, accessToken: String): WatchdogStatus {
    return client.get("${WatchdogApiConfig.baseUrl}/watchdog/status") {
        bearerAuth(accessToken)
    }.body()
}
