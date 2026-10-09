package net.kigawa.admin.server.mcp

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP Protocol models based on the Model Context Protocol specification.
 * Uses Streamable HTTP transport.
 */

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: @Contextual Any? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: @Contextual Any? = null,
    val result: @Contextual Any? = null,
    val error: McpError? = null
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: @Contextual Any? = null
)

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: InitializeCapabilities? = null,
    val clientInfo: ClientInfo? = null
)

@Serializable
data class InitializeCapabilities(
    val roots: RootsCapability? = null,
    val sampling: SamplingCapability? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null
)

/** Sampling capability - currently not implemented but required by spec. */
@Serializable
data class SamplingCapability(
    /** Reserved for future use */
    val supported: Boolean = false
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2025-06-18",
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: ResourcesCapability? = null,
    val prompts: PromptsCapability? = null,
    val logging: LoggingCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean = true
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = false
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean = false
)

/** Logging capability - server can send log notifications. */
@Serializable
data class LoggingCapability(
    /** Reserved for future use */
    val supported: Boolean = true
)

@Serializable
data class ServerInfo(
    val name: String = "admin-panel",
    val version: String = "1.0.0"
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ListToolsResult(
    val tools: List<Tool>,
    val nextCursor: String? = null
)

@Serializable
data class CallToolParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class CallToolResult(
    val content: List<ToolContent>,
    val isError: Boolean = false
)

/** Tool content types (text, image, audio, etc.) */
@Serializable
sealed class ToolContent {
    // MCP の content は {"type": "text", "text": "..."} の形が必須。
    // @SerialName を付けないと型名(net.kigawa...ToolContent.Text)が
    // discriminator として出てクライアントが解釈できない(実機で確認済み)。
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ToolContent()

    @Serializable
    @SerialName("image")
    data class Image(val data: String, val mimeType: String) : ToolContent()

    @Serializable
    @SerialName("audio")
    data class Audio(val data: String, val mimeType: String) : ToolContent()

    @Serializable
    @SerialName("resource")
    data class Resource(val resource: ResourceReference) : ToolContent()
}

@Serializable
data class ResourceReference(
    val uri: String,
    val name: String? = null,
    val mimeType: String? = null
)

@Serializable
data class ListResourcesResult(
    val resources: List<Resource>,
    val nextCursor: String? = null
)

@Serializable
data class Resource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class ReadResourceParams(
    val uri: String
)

@Serializable
data class ReadResourceResult(
    val contents: List<ResourceContent>
)

/** Resource content types (text, blob) */
@Serializable
@kotlinx.serialization.Polymorphic
sealed class ResourceContent {
    @Serializable
    @kotlinx.serialization.Polymorphic
    data class Text(val uri: String, val text: String, val mimeType: String? = null) : ResourceContent()
    
    @Serializable
    @kotlinx.serialization.Polymorphic
    data class Blob(val uri: String, val blob: String, val mimeType: String? = null) : ResourceContent()
}

@Serializable
data class ListPromptsResult(
    val prompts: List<Prompt>,
    val nextCursor: String? = null
)

@Serializable
data class Prompt(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>? = null
)

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)

@Serializable
data class GetPromptParams(
    val name: String,
    val arguments: Map<String, String>? = null
)

@Serializable
data class GetPromptResult(
    val description: String? = null,
    val messages: List<PromptMessage>
)

/** Prompt message content types */
@Serializable
@kotlinx.serialization.Polymorphic
sealed class PromptMessageContent {
    @Serializable
    @kotlinx.serialization.Polymorphic
    data class Text(val text: String) : PromptMessageContent()
}

@Serializable
data class PromptMessage(
    val role: String,
    val content: PromptMessageContent
)

@Serializable
data class LoggingMessage(
    val level: String,
    val data: @Contextual Any,
    val logger: String? = null
)

@Serializable
data class SetLevelParams(
    val level: String
)

@Serializable
data class CompletionParams(
    val ref: CompletionReference,
    val argument: CompletionArgument
)

/** Completion reference types */
@Serializable
@kotlinx.serialization.Polymorphic
sealed class CompletionReference {
    @Serializable
    @kotlinx.serialization.Polymorphic
    data class Prompt(val name: String) : CompletionReference()
    
    @Serializable
    @kotlinx.serialization.Polymorphic
    data class Resource(val uri: String) : CompletionReference()
}

@Serializable
data class CompletionArgument(
    val name: String,
    val value: String
)

@Serializable
data class CompletionResult(
    val completion: Completion
)

@Serializable
data class Completion(
    val values: List<String>,
    val total: Int? = null,
    val hasMore: Boolean = false
)

/** Notification types */
@Serializable
data class InitializedNotification(
    val dummy: Int = 0
)

@Serializable
data class CancelledNotification(
    val requestId: @Contextual Any,
    val reason: String? = null
)

@Serializable
data class ProgressNotification(
    val progressToken: @Contextual Any,
    val progress: Double,
    val total: Double? = null
)

@Serializable
data class LoggingNotification(
    val level: String,
    val data: @Contextual Any,
    val logger: String? = null
)

@Serializable
data class ResourceListChangedNotification(
    val dummy: Int = 0
)

@Serializable
data class ToolListChangedNotification(
    val dummy: Int = 0
)

@Serializable
data class PromptListChangedNotification(
    val dummy: Int = 0
)

@Serializable
data class ResourceUpdatedNotification(
    val uri: String
)

@Serializable
data class ResourceRemovedNotification(
    val uri: String
)

/**
 * MCP Method names
 */
object McpMethods {
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val RESOURCES_TEMPLATES_LIST = "resources/templates/list"
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
    const val LOGGING_SET_LEVEL = "logging/setLevel"
    const val COMPLETION = "completion"
    const val PING = "ping"
    const val CANCEL = "notifications/cancelled"
    const val PROGRESS = "notifications/progress"
    const val LOGGING = "notifications/message"
    const val RESOURCES_LIST_CHANGED = "notifications/resources/list_changed"
    const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
    const val PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed"
    const val RESOURCE_UPDATED = "notifications/resources/updated"
    const val RESOURCE_REMOVED = "notifications/resources/removed"
}

/**
 * MCP Error codes
 */
object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val METHOD_NOT_ALLOWED = -32000
    const val RESOURCE_NOT_FOUND = -32001
    const val INVALID_TOOL = -32002
    const val INVALID_RESOURCE = -32003
    const val PERMISSION_DENIED = -32004
}

/**
 * Extension to get string value from JsonElement
 */
val JsonElement.stringValue: String?
    get() = (this as? JsonPrimitive)?.content

/**
 * Extension to get int value from JsonElement
 */
val JsonElement.intValue: Int?
    get() = (this as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * Extension to get boolean value from JsonElement
 */
val JsonElement.boolValue: Boolean?
    get() = (this as? JsonPrimitive)?.content?.toBoolean()