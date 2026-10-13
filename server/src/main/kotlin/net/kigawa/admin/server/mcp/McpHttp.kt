package net.kigawa.admin.server.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * /mcp 専用の JSON 設定。グローバルの ContentNegotiation(REST 用)は変えず、MCP の応答だけに使う。
 *
 * - encodeDefaults=true: `"jsonrpc":"2.0"` を必ず出す(既定値のフィールドは省略しない)
 * - explicitNulls=false: null のフィールド(成功応答の error、失敗応答の result、
 *   capabilities の未使用項目など)は出さない。JSON-RPC は「result と error は排他」、
 *   MCP の capabilities の項目は省略可能(null ではない)のため。
 */
val mcpResponseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    serializersModule = mcpSerializersModule
}

/** [McpResponse] を /mcp 用の JSON 文字列にする。 */
fun encodeMcpResponse(response: McpResponse): String =
    mcpResponseJson.encodeToString(McpResponse.serializer(), response)

/** /mcp の JSON-RPC 応答を 200 で返す。 */
suspend fun respondMcp(call: ApplicationCall, response: McpResponse) {
    call.respondText(encodeMcpResponse(response), ContentType.Application.Json)
}

/**
 * 認証・認可失敗時の JSON-RPC エラー本文。リクエストの id は読めていないため null にする。
 * MCP クライアントは本文を JSON-RPC として解釈するため、401/403 でもこの形で返す。
 */
fun mcpAuthErrorBody(code: Int, message: String): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}.toString()

/** /mcp の認証・認可失敗を JSON-RPC エラー本文付きで返す(HTTP ステータスは [status] のまま)。 */
suspend fun respondMcpAuthError(call: ApplicationCall, status: HttpStatusCode, code: Int, message: String) {
    call.respondText(mcpAuthErrorBody(code, message), ContentType.Application.Json, status)
}
