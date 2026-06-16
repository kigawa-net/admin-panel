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
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.forms.Input
import com.varabyte.kobweb.silk.components.forms.InputType
import com.varabyte.kobweb.silk.components.text.SpanText
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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (val state = authState) {
            is AuthState.Unauthenticated -> LoginPage(
                onLogin = { u, p -> scope.launch { authProvider.login(u, p) } }
            )
            is AuthState.Loading -> LoginPage(isLoading = true, onLogin = { _, _ -> })
            is AuthState.Authenticated -> DashboardPage(
                username = state.username,
                onLogout = { authProvider.logout() }
            )
            is AuthState.Error -> LoginPage(
                error = state.message,
                onLogin = { u, p -> scope.launch { authProvider.login(u, p) } }
            )
        }
    }
}

@Composable
private fun LoginPage(
    isLoading: Boolean = false,
    error: String? = null,
    onLogin: (username: String, password: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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

            Input(
                type = InputType.Text,
                value = username,
                placeholder = "Username",
                enabled = !isLoading,
                onValueChanged = { username = it },
                modifier = Modifier.fillMaxWidth()
            )

            Input(
                type = InputType.Password,
                value = password,
                placeholder = "Password",
                enabled = !isLoading,
                onValueChanged = { password = it },
                modifier = Modifier.fillMaxWidth()
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
                onClick = {
                    if (!isLoading && username.isNotBlank() && password.isNotBlank()) {
                        onLogin(username, password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank()
            ) {
                SpanText(if (isLoading) "Signing in..." else "Sign In")
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
                Button(onClick = onLogout) {
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
