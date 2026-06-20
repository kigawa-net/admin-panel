package net.kigawa.admin.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import org.jetbrains.compose.web.css.*

@Page
@Composable
fun HomePage() {
    val authProvider = remember { KeycloakAuthProvider() }
    val authState by authProvider.authState.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(authProvider) {
        authProvider.init()
        onDispose { authProvider.close() }
    }

    when (val state = authState) {
        is AuthState.Unauthenticated -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoginPage(onLogin = { scope.launch { authProvider.startLogin() } })
        }
        is AuthState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoginPage(isLoading = true, onLogin = {})
        }
        is AuthState.Authenticated -> DashboardPage(
            username = state.username,
            onLogout = { authProvider.logout() }
        )
        is AuthState.Error -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoginPage(
                error = state.message,
                onLogin = { scope.launch { authProvider.startLogin() } }
            )
        }
    }
}

@Composable
private fun LoginPage(
    isLoading: Boolean = false,
    error: String? = null,
    onLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(400.px)
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(12.px)
            .boxShadow(offsetX = 0.px, offsetY = 4.px, blurRadius = 16.px, color = rgba(0, 0, 0, 0.1)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .padding(32.px)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            SpanText(
                "Admin Panel",
                modifier = Modifier
                    .fontSize(FontSize.XXLarge)
                    .fontWeight(FontWeight.Bold)
            )

            SpanText(
                "Sign in with Keycloak",
                modifier = Modifier
                    .fontSize(FontSize.Medium)
                    .color(Colors.Gray)
            )

            if (error != null) {
                SpanText(
                    error,
                    modifier = Modifier
                        .color(Colors.Red)
                        .fontSize(FontSize.Small)
                )
            }

            Button(
                onClick = { if (!isLoading) onLogin() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                SpanText(if (isLoading) "Signing in..." else "Sign in with Keycloak")
            }
        }
    }
}

@Composable
private fun DashboardPage(
    username: String,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(
                "Admin Panel",
                modifier = Modifier
                    .fontSize(FontSize.XLarge)
                    .fontWeight(FontWeight.Bold)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.px)
            ) {
                SpanText(username)
                Button(onClick = { onLogout() }) {
                    SpanText("Logout")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.px),
            verticalArrangement = Arrangement.spacedBy(24.px)
        ) {
            SpanText(
                "Dashboard",
                modifier = Modifier
                    .fontSize(FontSize.XXLarge)
                    .fontWeight(FontWeight.Bold)
            )
            SpanText("Welcome back, $username!")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.px)
            ) {
                StatCard("Users", "0")
                StatCard("Sessions", "1")
                StatCard("Roles", "0")
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String) {
    Column(
        modifier = Modifier
            .padding(24.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        SpanText(
            value,
            modifier = Modifier
                .fontSize(3.cssRem)
                .fontWeight(FontWeight.Bold)
        )
        SpanText(
            title,
            modifier = Modifier
                .fontSize(FontSize.Medium)
                .color(Colors.Gray)
        )
    }
}
