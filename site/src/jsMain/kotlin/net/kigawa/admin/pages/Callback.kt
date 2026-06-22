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
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.util.URLSearchParams

@Page("/callback")
@Composable
fun CallbackPage() {
    val authProvider = remember { KeycloakAuthProvider() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val params = URLSearchParams(window.location.search)
        val code = params.get("code")
        val state = params.get("state")
        val error = params.get("error")

        when {
            error != null -> window.location.href = "/?error=${js("encodeURIComponent(error)")}"
            code != null && state != null -> scope.launch {
                authProvider.handleCallback(code, state)
            }
            else -> window.location.href = "/"
        }

        onDispose { authProvider.close() }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SpanText("Signing in...")
    }
}

