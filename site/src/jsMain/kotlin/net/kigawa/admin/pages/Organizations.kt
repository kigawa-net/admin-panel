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
    // 組織管理は一般ユーザーも自分の組織を操作できる(管理者のみ削除等が可能)ため
    // 権限チェックはしない(issue #155の判断。組織スコープはRBACと別概念)。
    // AppShellのナビ表記と合わせる。
    AuthGuard { state, _, provider ->
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
            OrganizationPage(
                accessToken = state.accessToken,
                rbac = state.rbac,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
