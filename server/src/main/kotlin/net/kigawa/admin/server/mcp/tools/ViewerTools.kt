package net.kigawa.admin.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import net.kigawa.admin.server.AdminRole
import net.kigawa.admin.server.mcp.CallToolResult
import net.kigawa.admin.server.mcp.McpServer
import net.kigawa.admin.server.mcp.McpTool
import net.kigawa.admin.server.mcp.ToolContent
import net.kigawa.admin.server.mcp.intValue

/**
 * Do not report fabricated health, metrics, topology, server inventories, or
 * organization membership while viewer tools are not connected to real services.
 * See #225 and #261. No user-provided values are reflected in the response.
 */
internal fun viewerDataUnavailable(toolName: String): CallToolResult =
    CallToolResult(
        content = listOf(ToolContent.Text(
            text = "operation_unavailable: $toolName is not connected to a data service"
        )),
        isError = true,
    )

/**
 * Registers all MCP tools for viewer role
 */
fun McpServer.registerViewerTools() {
    registerTool(McpTool(
        name = "dashboard.get",
        description = "Get dashboard overview with key metrics (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            viewerDataUnavailable("dashboard.get")
        }
    ))

    registerTool(McpTool(
        name = "dashboard.getTraffic",
        description = "Get network traffic data (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("rangeMinutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Time range in minutes")
                    put("default", 60)
                })
            })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, args ->
            val rangeMinutes = args?.get("rangeMinutes")?.intValue ?: 60
            viewerDataUnavailable("dashboard.getTraffic")
        }
    ))

    registerTool(McpTool(
        name = "network.topology.get",
        description = "Get network topology graph (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            viewerDataUnavailable("network.topology.get")
        }
    ))

    registerTool(McpTool(
        name = "servers.list",
        description = "List all servers with their status (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            viewerDataUnavailable("servers.list")
        }
    ))

    registerTool(McpTool(
        name = "infrastructure.get",
        description = "Get infrastructure overview (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            viewerDataUnavailable("infrastructure.get")
        }
    ))

    registerTool(McpTool(
        name = "infrastructure.resourceUsage.get",
        description = "Get infrastructure resource usage metrics (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("rangeMinutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Time range in minutes")
                    put("default", 60)
                })
            })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, args ->
            val rangeMinutes = args?.get("rangeMinutes")?.intValue ?: 60
            viewerDataUnavailable("infrastructure.resourceUsage.get")
        }
    ))

    registerTool(McpTool(
        name = "organizations.mine",
        description = "Get organizations accessible to current user (currently unavailable: backend not connected)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            viewerDataUnavailable("organizations.mine")
        }
    ))
}