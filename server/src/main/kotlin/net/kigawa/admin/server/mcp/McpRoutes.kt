package net.kigawa.admin.server.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.kigawa.admin.server.AdminRole
import net.kigawa.admin.server.GithubInstallationQueryService
import net.kigawa.admin.server.requireRole
import net.kigawa.admin.server.mcp.tools.registerAllTools

/**
 * MCP endpoint installation
 *
 * MCP endpoint: /mcp
 * Uses Streamable HTTP transport
 * Must be called inside `authenticate("mcp")` (認証失敗時は401/403 の JSON-RPC エラー本文)。
 */
fun Route.mcpEndpoint(mcpServer: McpServer) {
    // MCP endpoint uses POST for JSON-RPC requests
    post("/mcp") {
        // Require authentication - viewer role is minimum for any MCP access
        val principal = requireRole(AdminRole.VIEWER, jsonRpcErrors = true) ?: return@post

        val requestBody = call.receive<McpRequest>()

        // 通知(id なし、または notifications/*)には JSON-RPC の応答を返さず、
        // Streamable HTTP の仕様どおり本文なしの 202 Accepted を返す。
        if (requestBody.id == null || requestBody.method.startsWith("notifications/")) {
            call.respond(HttpStatusCode.Accepted)
            return@post
        }

        val response = mcpServer.handleCall(call, principal, requestBody)
        respondMcp(call, response)
    }
}

/**
 * MCP Server instance - singleton per application
 */
object McpServerInstance {
    @Volatile
    private var instance: McpServer? = null

    fun getInstance(): McpServer {
        return instance ?: synchronized(this) {
            instance ?: McpServer().also { instance = it }
        }
    }

    fun initialize(githubInstallationQueries: GithubInstallationQueryService) {
        getInstance().registerAllTools(githubInstallationQueries)
    }
}
