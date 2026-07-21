package net.kigawa.admin.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.css.Cursor
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import net.kigawa.admin.auth.AuthGuard
import net.kigawa.admin.auth.KeycloakRealm
import org.jetbrains.compose.web.css.*

@Page
@Composable
fun HomePage() {
    val ctx = rememberPageContext()
    AuthGuard { state, logout ->
        DashboardPage(
            username = state.username,
            isAdmin = state.realm == KeycloakRealm.ADMIN,
            onLogout = logout,
            onOpenNetworkMap = { ctx.router.navigateTo("/network-map") },
            onOpenServers = { ctx.router.navigateTo("/servers") },
            onOpenUsers = { ctx.router.navigateTo("/users") },
            onOpenOrganizations = { ctx.router.navigateTo("/organizations") },
            onOpenGithubApp = { ctx.router.navigateTo("/github-app") }
        )
    }
}

@Composable
private fun DashboardPage(
    username: String,
    isAdmin: Boolean,
    onLogout: () -> Unit,
    onOpenNetworkMap: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenOrganizations: () -> Unit,
    onOpenGithubApp: () -> Unit
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.px)
                    .backgroundColor(Colors.White)
                    .borderRadius(8.px)
                    .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                    .onClick { onOpenNetworkMap() }
                    .cursor(Cursor.Pointer)
            ) {
                SpanText(
                    "ネットワークマップ",
                    modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
                )
                SpanText(
                    "kigawa-net の機器構成を図で確認する",
                    modifier = Modifier.color(Colors.Gray)
                )
            }

            if (isAdmin) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.px)
                        .backgroundColor(Colors.White)
                        .borderRadius(8.px)
                        .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                        .onClick { onOpenServers() }
                        .cursor(Cursor.Pointer)
                ) {
                    SpanText(
                        "サーバー管理",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
                    )
                    SpanText(
                        "各ノードの稼働状態を確認・操作する",
                        modifier = Modifier.color(Colors.Gray)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.px)
                        .backgroundColor(Colors.White)
                        .borderRadius(8.px)
                        .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                        .onClick { onOpenUsers() }
                        .cursor(Cursor.Pointer)
                ) {
                    SpanText(
                        "ユーザー管理",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
                    )
                    SpanText(
                        "Keycloakユーザーの作成・削除・パスワードリセットを行う",
                        modifier = Modifier.color(Colors.Gray)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.px)
                        .backgroundColor(Colors.White)
                        .borderRadius(8.px)
                        .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                        .onClick { onOpenOrganizations() }
                        .cursor(Cursor.Pointer)
                ) {
                    SpanText(
                        "組織管理",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
                    )
                    SpanText(
                        "組織の作成・削除とメンバー管理を行う",
                        modifier = Modifier.color(Colors.Gray)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.px)
                        .backgroundColor(Colors.White)
                        .borderRadius(8.px)
                        .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                        .onClick { onOpenGithubApp() }
                        .cursor(Cursor.Pointer)
                ) {
                    SpanText(
                        "GitHub App",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
                    )
                    SpanText(
                        "kigawa-net GitHub Appのインストールトークンを発行する",
                        modifier = Modifier.color(Colors.Gray)
                    )
                }
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
