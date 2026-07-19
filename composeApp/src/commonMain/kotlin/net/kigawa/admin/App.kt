package net.kigawa.admin

import androidx.compose.runtime.*
import net.kigawa.admin.auth.AuthState
import net.kigawa.admin.auth.KeycloakAuthProvider
import net.kigawa.admin.auth.KeycloakRealm
import net.kigawa.admin.networkmap.NetworkMapScreen
import net.kigawa.admin.organizations.OrganizationScreen
import net.kigawa.admin.screen.DashboardScreen
import net.kigawa.admin.screen.LoginScreen
import net.kigawa.admin.servers.ServerStatusScreen
import net.kigawa.admin.traffic.TrafficScreen
import net.kigawa.admin.users.UserManagementScreen

private sealed class AppScreen {
    object Dashboard : AppScreen()
    object NetworkMap : AppScreen()
    object Traffic : AppScreen()
    object Servers : AppScreen()
    object Users : AppScreen()
    object Organizations : AppScreen()
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
            LoginScreen(onLogin = { realm -> authProvider.login(realm) })
        }
        is AuthState.Loading -> {
            LoginScreen(isLoading = true, onLogin = {})
        }
        is AuthState.Authenticated -> {
            // サーバー管理・ユーザー管理画面はUI上も管理用realmのユーザーにのみ表示する。実際の
            // アクセス制御はバックエンド側でも独立に(realmごとに)検証されるため、これは利便性
            // のための制御。
            val isAdmin = state.realm == KeycloakRealm.ADMIN
            when (currentScreen) {
                AppScreen.Dashboard -> DashboardScreen(
                    username = state.username,
                    isAdmin = isAdmin,
                    onLogout = { authProvider.logout() },
                    onOpenNetworkMap = { currentScreen = AppScreen.NetworkMap },
                    onOpenTraffic = { currentScreen = AppScreen.Traffic },
                    onOpenServers = { currentScreen = AppScreen.Servers },
                    onOpenUsers = { currentScreen = AppScreen.Users },
                    onOpenOrganizations = { currentScreen = AppScreen.Organizations }
                )
                AppScreen.NetworkMap -> NetworkMapScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Traffic -> TrafficScreen(
                    accessToken = state.accessToken,
                    onBack = { currentScreen = AppScreen.Dashboard }
                )
                AppScreen.Servers -> if (isAdmin) {
                    ServerStatusScreen(
                        accessToken = state.accessToken,
                        onBack = { currentScreen = AppScreen.Dashboard }
                    )
                } else {
                    currentScreen = AppScreen.Dashboard
                }
                AppScreen.Users -> if (isAdmin) {
                    UserManagementScreen(
                        accessToken = state.accessToken,
                        onBack = { currentScreen = AppScreen.Dashboard }
                    )
                } else {
                    currentScreen = AppScreen.Dashboard
                }
                AppScreen.Organizations -> if (isAdmin) {
                    OrganizationScreen(
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
                onLogin = { realm -> authProvider.login(realm) }
            )
        }
    }
}
