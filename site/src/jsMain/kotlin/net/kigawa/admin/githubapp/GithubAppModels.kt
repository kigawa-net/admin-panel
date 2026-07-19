package net.kigawa.admin.githubapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the raw GitHub API shape (snake_case), since the server passes these fields through
 * unchanged from https://api.github.com/app/installations rather than translating them.
 */
@Serializable
data class GithubInstallationAccount(val login: String)

@Serializable
data class GithubInstallation(
    val id: Long,
    val account: GithubInstallationAccount? = null,
    @SerialName("repository_selection") val repositorySelection: String? = null,
    val permissions: Map<String, String> = emptyMap()
)

@Serializable
data class GithubInstallationTokenRequest(
    val repositories: List<String>? = null,
    val permissions: Map<String, String>? = null
)

@Serializable
data class GithubRepository(@SerialName("full_name") val fullName: String)

@Serializable
data class GithubInstallationTokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val permissions: Map<String, String> = emptyMap(),
    val repositories: List<GithubRepository>? = null
)
