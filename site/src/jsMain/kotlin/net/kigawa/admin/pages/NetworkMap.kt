package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.auth.KeycloakRealm
import net.kigawa.admin.layout.AppShell
import net.kigawa.admin.networkmap.NetworkMapPage

@Page("/network-map")
@Composable
fun NetworkMapRoute() {
    val ctx = rememberPageContext()
    AuthGuard { state, _ ->
        AppShell(isAdmin = state.realm == KeycloakRealm.ADMIN) {
            NetworkMapPage(
                accessToken = state.accessToken,
                onBack = { ctx.router.navigateTo("/") }
            )
        }
    }
}
