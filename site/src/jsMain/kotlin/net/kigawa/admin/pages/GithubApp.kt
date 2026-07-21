package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.githubapp.GithubAppPage

@Page("/github-app")
@Composable
fun GithubAppRoute() {
    val ctx = rememberPageContext()
    AuthGuard(requireAdmin = true) { state, _ ->
        GithubAppPage(
            accessToken = state.accessToken,
            onBack = { ctx.router.navigateTo("/") }
        )
    }
}
