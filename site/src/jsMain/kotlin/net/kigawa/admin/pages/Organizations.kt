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
import net.kigawa.admin.layout.AppShell
import net.kigawa.admin.organizations.OrganizationPage

@Page("/organizations")
@Composable
fun OrganizationsRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        // 単一レルム(manage)に統合されたため、認証済みなら常に管理者扱い
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
            isAdmin = true,
            accessToken = state.accessToken,
            currentOrgId = currentOrgId,
            onOrgChange = onOrgChange
        ) {
            OrganizationPage(
                accessToken = state.accessToken,
                isAdmin = true,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
