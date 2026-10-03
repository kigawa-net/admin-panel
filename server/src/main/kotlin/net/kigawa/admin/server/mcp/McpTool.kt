package net.kigawa.admin.server.mcp

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import net.kigawa.admin.server.AdminPrincipal
import net.kigawa.admin.server.AdminRole

/**
 * MCP Tool definition with role requirement
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiredRole: AdminRole,
    val handler: suspend (McpCallContext, JsonObject?) -> CallToolResult
)

/**
 * Context for MCP tool execution
 */
data class McpCallContext(
    val call: ApplicationCall,
    val principal: AdminPrincipal,
    val scope: CoroutineScope
)