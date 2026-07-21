package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.organizations.OrganizationPage

@Page("/organizations")
@Composable
fun OrganizationsRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        OrganizationPage(
            accessToken = state.accessToken,
            onBack = { ctx.router.navigateTo("/") }
        )
    }
}
