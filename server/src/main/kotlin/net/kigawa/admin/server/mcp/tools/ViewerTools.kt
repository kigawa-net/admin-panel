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
 * Registers all MCP tools for viewer role
 */
fun McpServer.registerViewerTools() {
    registerTool(McpTool(
        name = "dashboard.get",
        description = "Get dashboard overview with key metrics",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Dashboard: Cluster healthy, 7 nodes, 95% resource utilization"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "dashboard.getTraffic",
        description = "Get network traffic data",
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
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Traffic data for last $rangeMinutes minutes: In: 1.2 GB, Out: 850 MB"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "network.topology.get",
        description = "Get network topology graph",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Network topology: 7 nodes, 4 WireGuard tunnels, 3 ISP uplinks"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "servers.list",
        description = "List all servers with their status",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Servers: k8s-worker1 (healthy), k8s-worker3 (healthy), k8s1 (healthy), host1 (Proxmox)"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "infrastructure.get",
        description = "Get infrastructure overview",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Infrastructure: 4 Proxmox hosts, 7 K8s nodes, 2 ISP uplinks, 100% uptime"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "infrastructure.resourceUsage.get",
        description = "Get infrastructure resource usage metrics",
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
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Resource usage (last $rangeMinutes min): CPU 45%, Memory 62%, Disk 38%"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "organizations.mine",
        description = "Get organizations accessible to current user",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.VIEWER,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Your organizations: kigawa-net (owner)"
                )),
                isError = false
            )
        }
    ))
}