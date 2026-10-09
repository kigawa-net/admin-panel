package net.kigawa.admin.server.mcp

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.kigawa.admin.server.AdminPrincipal
import net.kigawa.admin.server.AdminRole

/**
 * MCP Server implementation for admin-panel
 * Provides tool registration and execution with RBAC
 */
class McpServer {
    private val tools = mutableMapOf<String, McpTool>()
    private val logger = org.slf4j.LoggerFactory.getLogger(McpServer::class.java)
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        // initialize の protocolVersion / serverInfo などの既定値を省略しない。
        // encodeDefaults=false(既定)だと initialize の応答から欠け、クライアントが
        // プロトコルバージョンを受け取れなくなる(実機 E2E で確認済み)。
        encodeDefaults = true
    }

    fun registerTool(tool: McpTool) {
        if (tools.containsKey(tool.name)) {
            throw IllegalArgumentException("Tool already registered: ${tool.name}")
        }
        tools[tool.name] = tool
        logger.info("Registered MCP tool: ${tool.name} (role: ${tool.requiredRole})")
    }

    fun getTool(name: String): McpTool? = tools[name]

    fun getToolsForRole(role: AdminRole): List<Tool> {
        return tools.values
            .filter { it.requiredRole in getRolesForRole(role) }
            .map { Tool(
                name = it.name,
                description = it.description,
                inputSchema = it.inputSchema
            ) }
    }

    /**
     * principal が見せてよいツール一覧。AdminPrincipal.roles は adminRolesFrom で
     * 包含関係(admin ⊃ operator ⊃ viewer)が展開済みなので、
     * そのまま contains で判定できる。
     */
    private fun visibleTools(principal: AdminPrincipal): List<Tool> =
        tools.values
            .filter { principal.hasRole(it.requiredRole) }
            .map { Tool(
                name = it.name,
                description = it.description,
                inputSchema = it.inputSchema
            ) }

    private fun getRolesForRole(role: AdminRole): Set<AdminRole> {
        return when (role) {
            AdminRole.VIEWER -> setOf(AdminRole.VIEWER)
            AdminRole.OPERATOR -> setOf(AdminRole.VIEWER, AdminRole.OPERATOR)
            AdminRole.ADMIN -> setOf(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.ADMIN)
        }
    }

    suspend fun handleCall(
        call: ApplicationCall,
        principal: AdminPrincipal,
        request: McpRequest
    ): McpResponse {
        val scope = CoroutineScope(Dispatchers.Default)
        val context = McpCallContext(call, principal, scope)

        return try {
            when (request.method) {
                McpMethods.INITIALIZE -> handleInitialize(request)
                McpMethods.TOOLS_LIST -> handleToolsList(request, principal)
                McpMethods.TOOLS_CALL -> handleToolsCall(context, request)
                McpMethods.PING -> McpResponse(
                    id = request.id,
                    result = buildJsonObject { },
                    error = null
                )
                else -> McpResponse(
                    id = request.id,
                    error = McpError(
                        code = McpErrorCodes.METHOD_NOT_FOUND,
                        message = "Method not found: ${request.method}"
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("MCP call failed: ${request.method}", e)
            McpResponse(
                id = request.id,
                error = McpError(
                    code = McpErrorCodes.INTERNAL_ERROR,
                    message = "Internal error: ${e.message}"
                )
            )
        }
    }

    private suspend fun handleInitialize(request: McpRequest): McpResponse {
        val params = request.params?.let { json.decodeFromJsonElement(InitializeParams.serializer(), it) }
        val result = InitializeResult(
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = true)
            ),
            serverInfo = ServerInfo()
        )
        return McpResponse(id = request.id, result = json.encodeToJsonElement(InitializeResult.serializer(), result))
    }

    private suspend fun handleToolsList(
        request: McpRequest,
        principal: AdminPrincipal
    ): McpResponse {
        // 役職の集合全体で可視ツールを絞る(以前は roles.firstOrNull() で
        // 1つだけ見ており、集合の走査順に結果が依存していた)。
        val result = ListToolsResult(tools = visibleTools(principal))
        return McpResponse(id = request.id, result = json.encodeToJsonElement(ListToolsResult.serializer(), result))
    }

    private suspend fun handleToolsCall(
        context: McpCallContext,
        request: McpRequest
    ): McpResponse {
        val params = request.params?.let { json.decodeFromJsonElement(CallToolParams.serializer(), it) }
        val toolName = params?.name
        val tool = tools[toolName ?: ""]

        if (tool == null) {
            return McpResponse(
                id = request.id,
                error = McpError(
                    code = McpErrorCodes.INVALID_TOOL,
                    message = "Tool not found: $toolName"
                )
            )
        }

        // tools/call 側にも権限判定を入れる。無いと viewer でも ADMIN 専用ツール
        // (users.list など)を直接呼べてしまう(実機 E2E で確認済み)。
        if (!context.principal.hasRole(tool.requiredRole)) {
            logger.warn(
                "MCP permission denied: user={} tool={} required={} actual={}",
                context.principal.userId, tool.name, tool.requiredRole, context.principal.roles
            )
            return McpResponse(
                id = request.id,
                error = McpError(
                    code = McpErrorCodes.PERMISSION_DENIED,
                    message = "Permission denied: ${tool.name} requires ${tool.requiredRole}"
                )
            )
        }

        // Execute tool
        try {
            val result = tool.handler(context, params?.arguments)
            return McpResponse(id = request.id, result = json.encodeToJsonElement(CallToolResult.serializer(), result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpResponse(
                id = request.id,
                error = McpError(
                    code = McpErrorCodes.INTERNAL_ERROR,
                    message = "Tool execution failed: ${e.message}"
                )
            )
        }
    }
}