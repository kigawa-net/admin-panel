package net.kigawa.admin.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.fillMaxSize
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.components.text.SpanText
import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.util.URLSearchParams

@Page("/callback")
@Composable
fun CallbackPage() {
    val authProvider = remember { KeycloakAuthProvider() }
    val authState by authProvider.authState.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val params = URLSearchParams(window.location.search)
        val code = params.get("code")
        val state = params.get("state")
        val error = params.get("error")

        when {
            error != null -> {
                val p = URLSearchParams()
                p.set("error", error)
                window.location.href = "/?$p"
            }
            code != null && state != null -> scope.launch {
                authProvider.handleCallback(code, state)
            }
            else -> window.location.href = "/"
        }

        onDispose { authProvider.close() }
    }

    LaunchedEffect(authState) {
        when (val s = authState) {
            is AuthState.Authenticated -> window.location.href = "/"
            is AuthState.Error -> {
                val p = URLSearchParams()
                p.set("error", s.message)
                window.location.href = "/?$p"
            }
            else -> Unit
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SpanText("Signing in...")
    }
}
