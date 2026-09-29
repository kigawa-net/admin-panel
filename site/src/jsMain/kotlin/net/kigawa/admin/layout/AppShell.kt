package net.kigawa.admin.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.organizations.Organization
import net.kigawa.admin.organizations.fetchMyOrganizations
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba
import kotlinx.coroutines.launch

private data class NavItem(val label: String, val path: String, val adminOnly: Boolean = false)

private val NAV_ITEMS = listOf(
    NavItem("ダッシュボード", "/"),
    NavItem("ネットワークマップ", "/network-map"),
    NavItem("ユーザー管理", "/users", adminOnly = true),
    NavItem("組織管理", "/organizations"),
    NavItem("インフラ構成", "/infrastructure", adminOnly = true),
    NavItem("GitHub App", "/github-app", adminOnly = true)
)

/** ログイン後の全ページを、常時表示のサイドナビゲーション付きレイアウトで包む。 */
@Composable
fun AppShell(
    isAdmin: Boolean,
    accessToken: String,
    currentOrgId: String?,
    onOrgChange: (String?) -> Unit,
    content: @Composable () -> Unit
) {
    val ctx = rememberPageContext()
    val currentPath = window.location.pathname
    var orgs by remember { mutableStateOf<List<Organization>>(emptyList()) }
    var orgLoading by remember { mutableStateOf(true) }
    val orgScope = rememberCoroutineScope()

    // 組織一覧を取得してOrgSwitcher用に保持
    LaunchedEffect(accessToken) {
        orgLoading = true
        try {
            val httpClient = HttpClient(Js) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            orgs = fetchMyOrganizations(httpClient, accessToken).organizations
        } catch (e: Exception) {
            // エラー時は空リスト
            orgs = emptyList()
        }
        orgLoading = false
    }

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

            // 組織切り替えセレクタ
            if (!orgLoading) {
                OrgSwitcher(
                    orgs = orgs,
                    currentOrgId = currentOrgId,
                    onOrgChange = onOrgChange
                )
            } else {
                SpanText("組織を読み込み中...", modifier = Modifier.padding(20.px).fontSize(FontSize.Small).color(Colors.Gray))
            }

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