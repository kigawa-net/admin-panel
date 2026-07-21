package net.kigawa.admin.servers

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

object ServerStatusApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchServerStatuses(client: HttpClient, accessToken: String): ServerStatusList {
    return client.get("${ServerStatusApiConfig.baseUrl}/servers") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun fetchPodsOnNode(client: HttpClient, accessToken: String, nodeName: String): PodList {
    return client.get("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/pods") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun cordonNode(client: HttpClient, accessToken: String, nodeName: String): ActionResult {
    return client.post("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/cordon") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun uncordonNode(client: HttpClient, accessToken: String, nodeName: String): ActionResult {
    return client.post("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/uncordon") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun drainNode(client: HttpClient, accessToken: String, nodeName: String): DrainResult {
    return client.post("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/drain") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun deletePod(client: HttpClient, accessToken: String, namespace: String, name: String): ActionResult {
    return client.delete("${ServerStatusApiConfig.baseUrl}/pods/$namespace/$name") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun gracefulShutdownNode(client: HttpClient, accessToken: String, nodeName: String, drainTimeoutSeconds: Int): ActionResult {
    return client.post("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/graceful-shutdown") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(GracefulShutdownRequest(drainTimeoutSeconds))
    }.body()
}

suspend fun gracefulRebootNode(client: HttpClient, accessToken: String, nodeName: String, drainTimeoutSeconds: Int): ActionResult {
    return client.post("${ServerStatusApiConfig.baseUrl}/servers/$nodeName/graceful-reboot") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(GracefulShutdownRequest(drainTimeoutSeconds))
    }.body()
}
