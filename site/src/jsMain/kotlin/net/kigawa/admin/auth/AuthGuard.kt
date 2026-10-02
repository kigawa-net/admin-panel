package net.kigawa.admin.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.kigawa.admin.util.URLSearchParams
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

/**
 * ページが要求する権限(issue #183)。
 * 表示制御はUX用であり、最終的な認可は必ずサーバー側のKtor API(requireRole)が
 * 同じロールで再判定する。
 */
enum class PagePermission {
    /** サーバー・ネットワーク・インフラ・メトリクスの閲覧(viewer) */
    VIEW_INFRASTRUCTURE,
    /** Cordon/Drain/Pod再起動・電源操作(operator) */
    OPERATE_SERVERS,
    /** ユーザー管理・組織削除など(admin) */
    MANAGE_USERS,
    /** GitHub App token発行・CI token policy(admin) */
    MANAGE_GITHUB_APP
}

/** [PagePermission] を [RbacPermissions] に解決する。 */
private fun RbacPermissions.hasPermission(permission: PagePermission): Boolean = when (permission) {
    PagePermission.VIEW_INFRASTRUCTURE -> canViewInfrastructure
    PagePermission.OPERATE_SERVERS -> canOperateServers
    PagePermission.MANAGE_USERS -> canManageUsers
    PagePermission.MANAGE_GITHUB_APP -> canManageGithubApp
}

/**
 * Shared Keycloak auth handling for every route: shows the login screen when unauthenticated,
 * surfaces auth errors, and (when [requirePermission] is set) bounces users who lack the
 * permission back to "/" instead of rendering [content].
 * 権限は userinfo のロール(admin-panelのclient role)から計算する。
 * Each `@Page` wraps its body in this instead of duplicating the auth dance.
 */
@Composable
fun AuthGuard(
    requirePermission: PagePermission? = null,
    content: @Composable (
        state: AuthState.Authenticated,
        logout: () -> Unit,
        provider: KeycloakAuthProvider
    ) -> Unit
) {
    val authProvider = remember { KeycloakAuthProvider() }
    val authState by authProvider.authState.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = rememberPageContext()

    val urlError = remember {
        URLSearchParams(window.location.search).get("error")
    }

    DisposableEffect(authProvider) {
        authProvider.init()
        if (urlError != null) authProvider.setError(urlError)
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
        is AuthState.Authenticated -> {
            // kigawa-net realmは誰でもセルフ登録できるため、認証済みだけでは権限を
            // 付与しない。権限が必要なページは userinfo のロール(admin-panelのclient role)
            // から計算した権限(state.rbac)が確認できたユーザーのみ表示し、それ以外は
            // ダッシュボードへ戻す。表示上の判定だけで、最終的な権限はサーバー側のRBACでも
            // 再判定する。
            val permitted = requirePermission == null || state.rbac.hasPermission(requirePermission)
            if (!permitted) {
                LaunchedEffect(Unit) {
                    ctx.router.navigateTo("/")
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SpanText("このページを表示する権限がありません")
                }
            } else {
                content(state, { authProvider.logoutAll() }, authProvider)
            }
        }
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
                SpanText(if (isLoading) "Signing in..." else "ログイン")
            }
        }
    }
}