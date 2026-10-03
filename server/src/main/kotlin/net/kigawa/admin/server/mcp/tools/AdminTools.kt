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
import net.kigawa.admin.server.mcp.intValue
import net.kigawa.admin.server.mcp.boolValue

/**
 * Registers all MCP tools for admin role
 */
fun McpServer.registerAdminTools() {
    registerTool(McpTool(
        name = "users.list",
        description = "List all users",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Users: kigawa (admin), tmp-viewer1 (viewer), tmp-viewer2 (operator), tmp-viewer3 (viewer)"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "users.create",
        description = "Create a new user",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Username")
                })
                put("email", buildJsonObject {
                    put("type", "string")
                    put("description", "Email")
                })
                put("role", buildJsonObject {
                    put("type", "string")
                    put("description", "Role (viewer/operator/admin)")
                    putJsonArray("enum") {
                        add(JsonPrimitive("viewer"))
                        add(JsonPrimitive("operator"))
                        add(JsonPrimitive("admin"))
                    }
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("username"))
                add(JsonPrimitive("email"))
                add(JsonPrimitive("role"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val username = args?.get("username")?.stringValue ?: ""
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "User $username created successfully"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "users.enable",
        description = "Enable a user",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Username")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("username"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val username = args?.get("username")?.stringValue ?: ""
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "User $username enabled"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "users.disable",
        description = "Disable a user",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Username")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("username"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val username = args?.get("username")?.stringValue ?: ""
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "User $username disabled"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "users.resetPassword",
        description = "Reset user password",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Username")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("username"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val username = args?.get("username")?.stringValue ?: ""
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Password reset for $username sent"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "users.delete",
        description = "Delete a user",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Username")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Confirm deletion")
                    put("default", false)
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("username"))
                add(JsonPrimitive("confirm"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val username = args?.get("username")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Deletion not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "User $username deleted"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "organizations.list",
        description = "List all organizations",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Organizations: kigawa-net, fusha, onemc, pixelsia"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "organizations.create",
        description = "Create a new organization",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Organization name")
                })
                put("domain", buildJsonObject {
                    put("type", "string")
                    put("description", "Domain")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("domain"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val name = args?.get("name")?.stringValue ?: ""
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "Organization $name created"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "organizations.delete",
        description = "Delete an organization",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Organization name")
                })
                put("confirm", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Confirm deletion")
                    put("default", false)
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("confirm"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val name = args?.get("name")?.stringValue ?: ""
            val confirm = args?.get("confirm")?.boolValue ?: false
            if (!confirm) {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Deletion not confirmed. Set confirm=true to proceed."
                    )),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(ToolContent.Text(
                        text = "Organization $name deleted"
                    )),
                    isError = false
                )
            }
        }
    ))

    registerTool(McpTool(
        name = "github_app.installations.list",
        description = "List GitHub App installations",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, _ ->
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "GitHub App installations: kigawa-net (active), fusha (pending)"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "github_app.ci_token.get",
        description = "Get CI token policy for GitHub App",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("installationId", buildJsonObject {
                    put("type", "integer")
                    put("description", "Installation ID")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("installationId"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val installationId = args?.get("installationId")?.intValue ?: 0
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "CI token policy for installation $installationId: 1h TTL, repo scope"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "github_app.ci_token.set",
        description = "Set CI token policy for GitHub App",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("installationId", buildJsonObject {
                    put("type", "integer")
                    put("description", "Installation ID")
                })
                put("ttlMinutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Token TTL in minutes")
                    put("default", 60)
                })
                put("repositories", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Repository names (empty = all)")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("installationId"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val installationId = args?.get("installationId")?.intValue ?: 0
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "CI token policy updated for installation $installationId"
                )),
                isError = false
            )
        }
    ))

    registerTool(McpTool(
        name = "github_app.ci_token.delete",
        description = "Delete CI token policy for GitHub App",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("installationId", buildJsonObject {
                    put("type", "integer")
                    put("description", "Installation ID")
                })
            })
            putJsonArray("required") {
                add(JsonPrimitive("installationId"))
            }
        },
        requiredRole = AdminRole.ADMIN,
        handler = { context, args ->
            val installationId = args?.get("installationId")?.intValue ?: 0
            CallToolResult(
                content = listOf(ToolContent.Text(
                    text = "CI token policy deleted for installation $installationId"
                )),
                isError = false
            )
        }
    ))
}