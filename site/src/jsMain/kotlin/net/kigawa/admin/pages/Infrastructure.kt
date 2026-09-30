package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import kotlinx.browser.localStorage
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.infrastructure.InfrastructurePage
import net.kigawa.admin.layout.AppShell

@Page("/infrastructure")
@Composable
fun InfrastructureRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _, provider ->
        var currentOrgId by remember { mutableStateOf<String?>(localStorage.getItem("selectedOrgId")) }
        val onOrgChange: (String?) -> Unit = { orgId ->
            currentOrgId = orgId
            if (orgId != null) {
                localStorage.setItem("selectedOrgId", orgId)
            } else {
                localStorage.removeItem("selectedOrgId")
            }
        }
        AppShell(
            isAdmin = state.isAdmin,
            accessToken = state.accessToken,
            currentOrgId = currentOrgId,
            onOrgChange = onOrgChange,
            authProvider = provider
        ) {
            InfrastructurePage(
                accessToken = state.accessToken,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
