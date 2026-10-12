package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.ToolContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CiPolicyMcpFailureTest {
    @Test
    fun `backend failure result does not contain exception or secret details`() {
        for (operation in listOf("list", "update", "delete")) {
            val result = ciPolicyFailureResult(operation)
            assertTrue(result.isError)
            val message = (result.content.single() as ToolContent.Text).text
            assertEquals("backend_unavailable: CI token policy $operation failed", message)
        }
    }
}
