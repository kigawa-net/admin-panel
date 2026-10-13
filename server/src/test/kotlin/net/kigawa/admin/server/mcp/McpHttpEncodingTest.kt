package net.kigawa.admin.server.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** /mcp の応答 JSON の形(JSON-RPC 2.0 の必須/排他ルール)。 */
class McpHttpEncodingTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun successResponseHasJsonRpcVersionAndResultButNoError() {
        val json = parse(encodeMcpResponse(McpResponse(id = 1, result = buildJsonObject { put("ok", true) })))
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, json["id"]?.jsonPrimitive?.int)
        assertTrue(json.containsKey("result"))
        assertFalse(json.containsKey("error"))
    }

    @Test
    fun errorResponseHasErrorButNoResult() {
        val json = parse(
            encodeMcpResponse(McpResponse(id = 1, error = McpError(code = -32601, message = "Method not found")))
        )
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("error"))
        assertFalse(json.containsKey("result"))
        assertEquals(-32601, json.getValue("error").jsonObject["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun stringIdIsEchoedAsString() {
        val json = parse(encodeMcpResponse(McpResponse(id = "abc-1", result = buildJsonObject { })))
        assertEquals("abc-1", json["id"]?.jsonPrimitive?.content)
        assertTrue(json["id"] is JsonPrimitive && json.getValue("id").jsonPrimitive.isString)
    }

    @Test
    fun numericIdIsEchoedAsNumber() {
        val json = parse(encodeMcpResponse(McpResponse(id = 42, result = buildJsonObject { })))
        assertFalse(json.getValue("id").jsonPrimitive.isString)
        assertEquals(42, json.getValue("id").jsonPrimitive.int)
    }

    @Test
    fun nullIdDoesNotBreakEncoding() {
        // 応答の id が無い(null)場合も、JSON-RPC の形(jsonrpc と error/result の排他)は保つ
        val json = parse(encodeMcpResponse(McpResponse(id = null, error = McpError(-32700, "Parse error"))))
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("error"))
        assertFalse(json.containsKey("result"))
    }

    @Test
    fun capabilityNullFieldsAreOmitted() {
        val result = mcpResponseJson.encodeToJsonElement(
            InitializeResult.serializer(),
            InitializeResult(capabilities = ServerCapabilities(tools = ToolsCapability(listChanged = true)), serverInfo = ServerInfo())
        )
        val caps = (result as JsonObject)["capabilities"]!!.jsonObject
        assertTrue(caps.containsKey("tools"))
        assertFalse(caps.containsKey("resources"))
        assertFalse(caps.containsKey("prompts"))
        assertFalse(caps.containsKey("logging"))
    }

    @Test
    fun authErrorBodyIsJsonRpcWithNullId() {
        val json = parse(mcpAuthErrorBody(McpErrorCodes.UNAUTHORIZED, "invalid or missing token"))
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, json["id"])
        assertEquals(-32001, json.getValue("error").jsonObject["code"]?.jsonPrimitive?.int)
        assertEquals("invalid or missing token", json.getValue("error").jsonObject["message"]?.jsonPrimitive?.content)
        assertNull(json["result"])
    }
}
