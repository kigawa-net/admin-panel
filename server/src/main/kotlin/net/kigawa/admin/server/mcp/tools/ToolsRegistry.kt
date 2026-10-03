package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.McpServer

/**
 * Registers all MCP tools for all roles
 */
fun McpServer.registerAllTools() {
    registerViewerTools()
    registerOperatorTools()
    registerAdminTools()
}
