package net.kigawa.admin

import androidx.compose.runtime.*
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.screen.DashboardScreen
import net.kigawa.admin.screen.LoginScreen

@Composable
fun App() {
    val authProvider = remember { KeycloakAuthProvider() }
    var authState by remember { mutableStateOf<AuthState>(AuthState.Unauthenticated) }

    LaunchedEffect(Unit) {
        authProvider.authState.collect { state ->
            authState = state
        }
    }

    when (authState) {
        is AuthState.Unauthenticated -> {
            LoginScreen(
                onLogin = { username, password ->
                    authProvider.login(username, password)
                }
            )
        }
        is AuthState.Loading -> {
            LoginScreen(
                isLoading = true,
                onLogin = { _, _ -> }
            )
        }
        is AuthState.Authenticated -> {
            DashboardScreen(
                username = (authState as AuthState.Authenticated).username,
                onLogout = { authProvider.logout() }
            )
        }
        is AuthState.Error -> {
            LoginScreen(
                error = (authState as AuthState.Error).message,
                onLogin = { username, password ->
                    authProvider.login(username, password)
                }
            )
        }
    }
}
