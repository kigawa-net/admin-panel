package net.kigawa.admin.githubapp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import net.kigawa.admin.servers.ServerStatusApiConfig

suspend fun fetchGithubInstallations(client: HttpClient, accessToken: String): List<GithubInstallation> {
    return client.get("${ServerStatusApiConfig.baseUrl}/github-app/installations") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun issueGithubInstallationToken(
    client: HttpClient,
    accessToken: String,
    installationId: Long,
    repositories: List<String>?,
    permissions: Map<String, String>?
): GithubInstallationTokenResponse {
    return client.post("${ServerStatusApiConfig.baseUrl}/github-app/installations/$installationId/token") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(GithubInstallationTokenRequest(repositories = repositories, permissions = permissions))
    }.body()
}
