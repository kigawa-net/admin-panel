package net.kigawa.admin.githubapp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

suspend fun fetchCiTokenPolicies(client: HttpClient, accessToken: String): List<CiTokenPolicyEntry> {
    return client.get("${ServerStatusApiConfig.baseUrl}/github-app/ci-policy") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun upsertCiTokenPolicy(client: HttpClient, accessToken: String, entry: CiTokenPolicyEntry): CiTokenPolicyEntry {
    return client.put("${ServerStatusApiConfig.baseUrl}/github-app/ci-policy/${entry.callerRepository}") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(entry)
    }.body()
}

suspend fun deleteCiTokenPolicy(client: HttpClient, accessToken: String, callerRepository: String) {
    client.delete("${ServerStatusApiConfig.baseUrl}/github-app/ci-policy/$callerRepository") {
        bearerAuth(accessToken)
    }
}
