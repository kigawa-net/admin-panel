package net.kigawa.admin.server.mcp

import io.ktor.server.auth.authenticate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import net.kigawa.admin.server.AdminRole
import net.kigawa.admin.server.requireRole
import net.kigawa.admin.server.mcp.tools.registerAllTools

/**
 * MCP endpoint installation
 * 
 * MCP endpoint: /mcp
 * Uses Streamable HTTP transport
 * Requires authentication via existing JWT authentication
 */
fun io.ktor.server.routing.Routing.mcpEndpoint(mcpServer: McpServer) {
    // MCP endpoint uses POST for JSON-RPC requests
    post("/mcp") {
        // Require authentication - viewer role is minimum for any MCP access
        val principal = requireRole(AdminRole.VIEWER) ?: return@post
        
        val requestBody = call.receive<McpRequest>()
        
        val response = mcpServer.handleCall(call, principal, requestBody)
        call.respond(response)
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

    fun initialize() {
        getInstance().registerAllTools()
    }
}