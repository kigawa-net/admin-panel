package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.ToolContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorToolsFailClosedTest {
    @Test
    fun `unimplemented operator action never reports success`() {
        val operations = listOf(
            "servers.cordon",
            "servers.uncordon",
            "servers.drain",
            "servers.reboot",
            "servers.shutdown",
            "pods.restart",
        )
        for (name in operations) {
            val result = operatorActionUnavailable(name)
            assertTrue(result.isError, "Unimplemented $name must fail closed")
            val message = (result.content.single() as ToolContent.Text).text
            assertTrue(message.startsWith("operation_unavailable:"))
            assertTrue(message.contains(name))
        }
    }
}
