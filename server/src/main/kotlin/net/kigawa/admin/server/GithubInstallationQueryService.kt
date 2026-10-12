package net.kigawa.admin.server

/**
 * Shared read-only boundary for the GitHub App installation inventory.
 * Both REST and MCP use the same query implementation; no installation tokens
 * or private-key material are included in [GithubInstallation].
 */
fun interface GithubInstallationQueryService {
    suspend fun list(): List<GithubInstallation>
}
