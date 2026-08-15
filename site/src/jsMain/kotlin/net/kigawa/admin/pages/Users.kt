package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.auth.KeycloakRealm
import net.kigawa.admin.layout.AppShell
import net.kigawa.admin.users.UserManagementPage

@Page("/users")
@Composable
fun UsersRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        AppShell(isAdmin = state.realm == KeycloakRealm.ADMIN) {
            UserManagementPage(
                accessToken = state.accessToken,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
