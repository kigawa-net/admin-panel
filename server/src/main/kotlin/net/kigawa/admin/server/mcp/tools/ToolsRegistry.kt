package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.McpServer
import net.kigawa.admin.server.GithubInstallationQueryService

/**
 * Registers all MCP tools for all roles
 */
fun McpServer.registerAllTools(githubInstallationQueries: GithubInstallationQueryService) {
    registerViewerTools()
    registerOperatorTools()
    registerAdminTools(githubInstallationQueries)
}
