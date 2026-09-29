package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.layout.AppShell
import net.kigawa.admin.organizations.OrganizationPage

@Page("/organizations")
@Composable
fun OrganizationsRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        // 単一レルム(manage)に統合されたため、認証済みなら常に管理者扱い
        AppShell(isAdmin = true) {
            OrganizationPage(
                accessToken = state.accessToken,
                isAdmin = true,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
