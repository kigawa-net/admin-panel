package net.kigawa.admin

import androidx.compose.runtime.*
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.networkmap.NetworkMapScreen
import net.kigawa.admin.screen.DashboardScreen
import net.kigawa.admin.screen.LoginScreen

private sealed class AppScreen {
    object Dashboard : AppScreen()
    object NetworkMap : AppScreen()
}

@Composable
fun App() {
    val authProvider = remember { KeycloakAuthProvider() }
    var authState by remember { mutableStateOf<AuthState>(AuthState.Unauthenticated) }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Dashboard) }

    LaunchedEffect(Unit) {
        authProvider.authState.collect { state ->
            authState = state
        }
    }

    when (val state = authState) {
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
            when (currentScreen) {
                AppScreen.Dashboard -> DashboardScreen(
                    username = state.username,
                    onLogout = { authProvider.logout() },
                    onOpenNetworkMap = { currentScreen = AppScreen.NetworkMap }
                )
                AppScreen.NetworkMap -> NetworkMapScreen(
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
            }
        }
        is AuthState.Error -> {
            LoginScreen(
                error = state.message,
                onLogin = { username, password ->
                    authProvider.login(username, password)
                }
            )
        }
    }
}
