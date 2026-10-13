package net.kigawa.admin.server.mcp

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.admin.server.RbacConfig
import net.kigawa.admin.server.TokenFactory
import net.kigawa.admin.server.mcpJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /mcp のHTTP応答(JSON-RPC 2.0 の形・通知の202・認証/認可失敗時のJSON-RPCエラー本文)。
 *
 * 本番と同じ構成(グローバル ContentNegotiation + mcpJwt + authenticate("mcp") + mcpEndpoint)で
 * 実ルートを通す。JWKS はテスト用の公開鍵を直接渡す。
 */
class McpHttpRouteTest {
    private val tokens = TokenFactory()

    private class McpResult(val status: HttpStatusCode, val headers: Headers, val bodyText: String) {
        val json: JsonObject get() = Json.parseToJsonElement(bodyText).jsonObject
    }

    private inner class Probe(private val client: HttpClient) {
        suspend fun post(body: String, token: String?): McpResult {
            val res = client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody(body)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            return McpResult(res.status, res.headers, res.bodyAsText())
        }
    }

    private fun withMcp(block: suspend Probe.() -> Unit) = runBlocking {
        testApplication {
            application { mcpTestModule() }
            Probe(client).block()
        }
        Unit
    }

    private fun viewerToken() = tokens.token(iss = ISSUER, aud = listOf(MCP_AUDIENCE), roles = listOf("viewer"))

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun initializeResponseIsJsonRpc20WithMatchingId() = withMcp {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                """"params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            viewerToken()
        )
        assertEquals(HttpStatusCode.OK, res.status, res.bodyText)
        assertEquals("2.0", res.json.str("jsonrpc"), "jsonrpc must be \"2.0\": ${res.bodyText}")
        assertEquals(1, res.json.getValue("id").jsonPrimitive.int)
        val result = res.json.getValue("result").jsonObject
        assertNotNull(result["protocolVersion"])
        assertNotNull(result["serverInfo"])
        assertNull(res.json["error"])
    }

    @Test
    fun toolsListResponseKeepsStringIdAndResult() = withMcp {
        val res = post("""{"jsonrpc":"2.0","id":"req-2","method":"tools/list"}""", viewerToken())
        assertEquals(HttpStatusCode.OK, res.status, res.bodyText)
        assertEquals("2.0", res.json.str("jsonrpc"))
        assertEquals("req-2", res.json.str("id"))
        assertNotNull(res.json.getValue("result").jsonObject["tools"])
        assertNull(res.json["error"])
    }

    @Test
    fun notificationIsAcceptedWithEmptyBody() = withMcp {
        val res = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", viewerToken())
        assertEquals(HttpStatusCode.Accepted, res.status, res.bodyText)
        assertEquals("", res.bodyText)
    }

    @Test
    fun unauthenticatedReturns401JsonRpcErrorWithChallenge() = withMcp {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = null)
        assertEquals(HttpStatusCode.Unauthorized, res.status, res.bodyText)
        assertTrue(res.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("resource_metadata="), "headers=${res.headers.names()} www=${res.headers[HttpHeaders.WWWAuthenticate]}")
        assertEquals("2.0", res.json.str("jsonrpc"), "401 body must be JSON-RPC: ${res.bodyText}")
        assertEquals(-32001, res.json.getValue("error").jsonObject["code"]?.jsonPrimitive?.int)
        assertEquals(JsonNull, res.json["id"])
        assertNull(res.json["result"])
    }

    @Test
    fun roleLessTokenReturns403JsonRpcError() = withMcp {
        val token = tokens.token(iss = ISSUER, aud = listOf(MCP_AUDIENCE), roles = emptyList())
        val res = post("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""", token)
        assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyText)
        assertEquals("2.0", res.json.str("jsonrpc"), "403 body must be JSON-RPC: ${res.bodyText}")
        // 403 は requireRole 内で返すためリクエストの id を読めておらず、id は null になる
        assertEquals(JsonNull, res.json["id"])
        assertNotNull(res.json.getValue("error").jsonObject["message"])
        assertNull(res.json["result"])
    }

    private fun Application.mcpTestModule() {
        // 本番(Application.kt)のグローバル ContentNegotiation と同じ設定
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                serializersModule = mcpSerializersModule
            })
        }
        install(Authentication) {
            mcpJwt(testConfig(), StaticJwkProvider(tokens.jwk(tokens.kid)))
        }
        routing {
            authenticate("mcp") {
                mcpEndpoint(McpServer())
            }
        }
    }

    private class StaticJwkProvider(private val jwk: Jwk) : JwkProvider {
        override fun get(keyId: String?): Jwk = jwk
    }

    private fun testConfig() = RbacConfig(
        jwksUrl = "https://example.invalid/jwks",
        issuer = ISSUER,
        audience = "admin-panel",
        azp = null,
        rbacEnforced = true
    )

    companion object {
        private const val ISSUER = "https://example.invalid/realms/test"
        private const val MCP_AUDIENCE = "https://admin.kigawa.net/mcp"
    }
}
