package net.kigawa.admin

import androidx.compose.runtime.*
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.infrastructure.InfrastructureScreen
import net.kigawa.admin.networkmap.NetworkMapScreen
import net.kigawa.admin.organizations.OrganizationScreen
import net.kigawa.admin.screen.DashboardScreen
import net.kigawa.admin.screen.LoginScreen
import net.kigawa.admin.traffic.TrafficScreen
import net.kigawa.admin.users.UserManagementScreen

private sealed class AppScreen {
    object Dashboard : AppScreen()
    object NetworkMap : AppScreen()
    object Traffic : AppScreen()
    object Users : AppScreen()
    object Organizations : AppScreen()
    object Infrastructure : AppScreen()
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
            // kigawa-net realmは誰でもセルフ登録できるため、認証済みだけでは管理者にしない。
            // 管理者は userinfo のロール(admin-panelのadminロール)で判定し、
            // ロールが確認できない間は非管理者(安全側)とする。
            val isAdmin = state.isAdmin
            when (currentScreen) {
                AppScreen.Dashboard -> DashboardScreen(
                    username = state.username,
                    isAdmin = isAdmin,
                    onLogout = { authProvider.logout() },
                    onOpenNetworkMap = { currentScreen = AppScreen.NetworkMap },
                    onOpenTraffic = { currentScreen = AppScreen.Traffic },
                    onOpenUsers = { currentScreen = AppScreen.Users },
                    onOpenOrganizations = { currentScreen = AppScreen.Organizations },
                    onOpenInfrastructure = { currentScreen = AppScreen.Infrastructure }
                )
                AppScreen.NetworkMap -> NetworkMapScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Traffic -> TrafficScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Users -> if (isAdmin) {
                    UserManagementScreen(
                        accessToken = state.accessToken,
                        onBack = { currentScreen = AppScreen.Dashboard }
                    )
                } else {
                    currentScreen = AppScreen.Dashboard
                }
                AppScreen.Organizations -> OrganizationScreen(
                    accessToken = state.accessToken,
                    isAdmin = isAdmin,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Infrastructure -> if (isAdmin) {
                    InfrastructureScreen(
                        accessToken = state.accessToken,
                        onBack = { currentScreen = AppScreen.Dashboard }
                    )
                } else {
                    currentScreen = AppScreen.Dashboard
                }
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