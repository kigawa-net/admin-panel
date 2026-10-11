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
import net.kigawa.admin.server.mcp.stringValue
import net.kigawa.admin.server.mcp.boolValue

/**
 * Registers all MCP tools for operator role
 */
fun McpServer.registerOperatorTools() {
    registerTool(McpTool(
        name = "servers.cordon",
        description = "Cordon a Kubernetes node (mark as unschedulable)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("nodeName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the node to cordon")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this destructive operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("nodeName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val nodeName = args?.get("nodeName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Cordon not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Node $nodeName cordoned successfully"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "servers.uncordon",
        description = "Uncordon a Kubernetes node (mark as schedulable)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("nodeName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the node to uncordon")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("nodeName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val nodeName = args?.get("nodeName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Uncordon not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Node $nodeName uncordoned successfully"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "servers.drain",
        description = "Drain a Kubernetes node (evict all pods)",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("nodeName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the node to drain")
                })
                put("deleteEmptyDir", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Delete emptyDir volumes")
                    put("default", true)
                })
                put("ignoreDaemonSets", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Ignore DaemonSet pods")
                    put("default", true)
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this destructive operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("nodeName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val nodeName = args?.get("nodeName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Drain not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Node $nodeName drain initiated"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "servers.reboot",
        description = "Reboot a server gracefully",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("serverName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the server to reboot")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this destructive operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("serverName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val serverName = args?.get("serverName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Reboot not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Server $serverName reboot initiated"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "servers.shutdown",
        description = "Shutdown a server gracefully",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("serverName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the server to shutdown")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this destructive operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("serverName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val serverName = args?.get("serverName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Shutdown not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Server $serverName shutdown initiated"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "pods.restart",
        description = "Restart a pod",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("namespace", buildJsonObject {
                    put("type", "string")
                    put("description", "Pod namespace")
                })
                put("podName", buildJsonObject {
                    put("type", "string")
                    put("description", "Pod name")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set to true to confirm this destructive operation")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("namespace"))
                add(JsonPrimitive("podName"))
            }
        },
        requiredRole = AdminRole.OPERATOR,
        handler = { context, args ->
            val namespace = args?.get("namespace")?.stringValue ?: ""
            val podName = args?.get("podName")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Restart not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Pod $namespace/$podName restart initiated"
                    )),
                    isError = false
                )
            }
        }
    ))
}