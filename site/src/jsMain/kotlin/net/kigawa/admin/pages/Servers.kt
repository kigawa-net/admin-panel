package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.servers.ServerStatusPage

@Page("/servers")
@Composable
fun ServersRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        ServerStatusPage(
            accessToken = state.accessToken,
            onBack = { ctx.router.navigateTo("/") }
        )
    }
}
