package net.kigawa.admin

import androidx.compose.runtime.*
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.networkmap.NetworkMapScreen
import net.kigawa.admin.screen.DashboardScreen
import net.kigawa.admin.screen.LoginScreen
import net.kigawa.admin.servers.ServerStatusScreen
import net.kigawa.admin.traffic.TrafficScreen

private sealed class AppScreen {
    object Dashboard : AppScreen()
    object NetworkMap : AppScreen()
    object Traffic : AppScreen()
    object Servers : AppScreen()
}

@Composable
fun App(authProvider: KeycloakAuthProvider) {
    var authState by remember { mutableStateOf<AuthState>(AuthState.Unauthenticated) }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Dashboard) }

    LaunchedEffect(authProvider) {
        authProvider.authState.collect { state ->
            authState = state
        }
    }

    when (val state = authState) {
        is AuthState.Unauthenticated -> {
            LoginScreen(onLogin = { authProvider.login() })
        }
        is AuthState.Loading -> {
            LoginScreen(isLoading = true, onLogin = {})
        }
        is AuthState.Authenticated -> {
            when (currentScreen) {
                AppScreen.Dashboard -> DashboardScreen(
                    username = state.username,
                    onLogout = { authProvider.logout() },
                    onOpenNetworkMap = { currentScreen = AppScreen.NetworkMap },
                    onOpenTraffic = { currentScreen = AppScreen.Traffic },
                    onOpenServers = { currentScreen = AppScreen.Servers }
                )
                AppScreen.NetworkMap -> NetworkMapScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Traffic -> TrafficScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Servers -> ServerStatusScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
            }
        }
        is AuthState.Error -> {
            LoginScreen(
                error = state.message,
                onLogin = { authProvider.login() }
            )
        }
    }
}
