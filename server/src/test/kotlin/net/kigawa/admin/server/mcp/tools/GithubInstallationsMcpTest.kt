package net.kigawa.admin.server.mcp.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.kigawa.admin.server.GithubInstallation
import net.kigawa.admin.server.GithubInstallationAccount
import net.kigawa.admin.server.GithubInstallationQueryService
import net.kigawa.admin.server.mcp.ToolContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GithubInstallationsMcpTest {
    @Test
    fun `installation inventory MCP result includes actual permissions and selection`() = runBlocking {
        val inventory = listOf(
            GithubInstallation(
                id = 42L,
                account = GithubInstallationAccount(login = "sample-org"),
                repositorySelection = "selected",
                permissions = mapOf("contents" to "write", "pull_requests" to "read"),
            )
        )
        val query = GithubInstallationQueryService { inventory }

        val result = githubInstallationsToMcpResult(query.list())

        assertFalse(result.isError)
        val message = (result.content.single() as ToolContent.Text).text
        val decoded = Json.decodeFromString<List<GithubInstallation>>(message)
        assertEquals(42L, decoded.single().id)
        assertEquals("sample-org", decoded.single().account?.login)
        assertEquals("selected", decoded.single().repositorySelection)
        assertEquals("write", decoded.single().permissions["contents"])
        assertEquals("read", decoded.single().permissions["pull_requests"])
        assertFalse(message.contains("installation_token", ignoreCase = true))
        assertFalse(message.contains("private_key", ignoreCase = true))
    }

    @Test
    fun `empty installation inventory is represented as empty list rather than fabricated data`() {
        val result = githubInstallationsToMcpResult(emptyList())
        assertFalse(result.isError)
        val message = (result.content.single() as ToolContent.Text).text
        assertEquals("[]", message)
        assertTrue(message.none { it == '\n' })
    }
}
