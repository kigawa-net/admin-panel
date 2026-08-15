package net.kigawa.admin.layout

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.css.Cursor
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.silk.components.text.SpanText
import kotlinx.browser.window
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private data class NavItem(val label: String, val path: String, val adminOnly: Boolean = false)

private val NAV_ITEMS = listOf(
    NavItem("ダッシュボード", "/"),
    NavItem("ネットワークマップ", "/network-map"),
    NavItem("サーバー管理", "/servers", adminOnly = true),
    NavItem("ユーザー管理", "/users", adminOnly = true),
    NavItem("組織管理", "/organizations"),
    NavItem("インフラ構成", "/infrastructure", adminOnly = true),
    NavItem("GitHub App", "/github-app", adminOnly = true)
)

/** ログイン後の全ページを、常時表示のサイドナビゲーション付きレイアウトで包む。 */
@Composable
fun AppShell(isAdmin: Boolean, content: @Composable () -> Unit) {
    val ctx = rememberPageContext()
    val currentPath = window.location.pathname

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(220.px)
                .fillMaxHeight()
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 2.px, offsetY = 0.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08))
                .padding(topBottom = 16.px),
            verticalArrangement = Arrangement.spacedBy(2.px)
        ) {
            SpanText(
                "Admin Panel",
                modifier = Modifier
                    .fontSize(FontSize.Large)
                    .fontWeight(FontWeight.Bold)
                    .padding(leftRight = 20.px, bottom = 16.px)
            )
            NAV_ITEMS.filter { !it.adminOnly || isAdmin }.forEach { item ->
                val active = currentPath == item.path
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .padding(leftRight = 20.px, topBottom = 10.px)
                    .onClick { if (!active) ctx.router.navigateTo(item.path) }
                    .cursor(if (active) Cursor.Default else Cursor.Pointer)
                    .let { if (active) it.backgroundColor(rgba(42, 120, 214, 0.1)) else it }
                Row(modifier = rowModifier) {
                    SpanText(
                        item.label,
                        modifier = Modifier
                            .fontSize(FontSize.Small)
                            .color(if (active) Color("#2A78D6") else Colors.Black)
                            .fontWeight(if (active) FontWeight.Bold else FontWeight.Normal)
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
