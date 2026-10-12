package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.ToolContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminToolsFailClosedTest {
    @Test
    fun `all unimplemented administrative tools reject success`() {
        val names = listOf(
            "users.list",
            "users.create",
            "users.enable",
            "users.disable",
            "users.resetPassword",
            "users.delete",
            "organizations.list",
            "organizations.create",
            "organizations.delete",
            "github_app.ci_token.get",
            "github_app.ci_token.set",
            "github_app.ci_token.delete",
        )
        for (name in names) {
            val result = unavailableAdminMcpTool(name)
            assertTrue(result.isError, "Stub $name must not claim successful execution")
            val text = (result.content.single() as ToolContent.Text).text
            assertTrue(text.startsWith("operation_unavailable:"))
            assertTrue(text.contains(name))
            assertFalse(text.contains("password", ignoreCase = true))
            assertFalse(text.contains("token=", ignoreCase = true))
        }
    }
}
