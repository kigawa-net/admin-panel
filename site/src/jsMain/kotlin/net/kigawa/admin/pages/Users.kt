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
import net.kigawa.admin.auth.PagePermission
import net.kigawa.admin.layout.AppShell
import net.kigawa.admin.users.UserManagementPage

@Page("/users")
@Composable
fun UsersRoute() {
    val ctx = rememberPageContext()
    // ユーザー管理は admin ロールのみ(issue #183)
    AuthGuard(requirePermission = PagePermission.MANAGE_USERS) { state, _, provider ->
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
            rbac = state.rbac,
            accessToken = state.accessToken,
            currentOrgId = currentOrgId,
            onOrgChange = onOrgChange,
            authProvider = provider
        ) {
            UserManagementPage(
                accessToken = state.accessToken,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
