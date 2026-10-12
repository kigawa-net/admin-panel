package net.kigawa.admin.server.mcp.tools

import net.kigawa.admin.server.mcp.ToolContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerToolsFailClosedTest {
    @Test
    fun `viewer tools never invent healthy infrastructure or access lists`() {
        val tools = listOf(
            "dashboard.get",
            "dashboard.getTraffic",
            "network.topology.get",
            "servers.list",
            "infrastructure.get",
            "infrastructure.resourceUsage.get",
            "organizations.mine",
        )
        for (toolName in tools) {
            val result = viewerDataUnavailable(toolName)
            assertTrue(result.isError, "$toolName cannot report success without a data service")
            val message = (result.content.single() as ToolContent.Text).text
            assertTrue(message.startsWith("operation_unavailable:"))
            assertTrue(message.contains(toolName))
            assertFalse(message.contains("healthy", ignoreCase = true))
            assertFalse(message.contains("uptime", ignoreCase = true))
            assertFalse(message.contains("resource utilization", ignoreCase = true))
        }
    }
}
