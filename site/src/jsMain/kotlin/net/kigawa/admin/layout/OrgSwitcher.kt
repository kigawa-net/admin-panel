package net.kigawa.admin.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.compose.css.Cursor
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.forms.TextInput
import com.varabyte.kobweb.silk.components.text.SpanText
import kotlinx.browser.localStorage
import kotlinx.browser.window
import net.kigawa.admin.organizations.Organization
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private const val ORG_ID_KEY = "selectedOrgId"

/**
 * 組織切り替えセレクタ。
 * ユーザーが所属する組織一覧を受け取り、ドロップダウンで切り替え可能にする。
 * 選択された orgId は localStorage に保存され、ページ遷移時にも維持される。
 */
@Composable
fun OrgSwitcher(
    orgs: List<Organization>,
    currentOrgId: String?,
    onOrgChange: (String?) -> Unit
) {
    var showDropdown by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 現在の組織名を取得
    val currentOrg = orgs.find { it.id == currentOrgId }
    val displayName = currentOrg?.name ?: "組織を選択"

    Column(
        modifier = Modifier
            .padding(8.px)
            .width(200.px)
    ) {
        // ドロップダウントリガー
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onClick { showDropdown = !showDropdown }
                .cursor(Cursor.Pointer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.px)
                    .backgroundColor(Colors.White)
                    .borderRadius(6.px)
                    .border(width = 1.px, color = rgba(0, 0, 0, 0.12)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpanText(displayName, modifier = Modifier.fontSize(FontSize.Small))
                SpanText("▼", modifier = Modifier.fontSize(10.px).color(Colors.Gray))
            }
        }

        // ドロップダウンメニュー
        if (showDropdown) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .margin(top = 4.px)
                    .padding(8.px)
                    .backgroundColor(Colors.White)
                    .borderRadius(6.px)
                    .border(width = 1.px, color = rgba(0, 0, 0, 0.12))
                    .boxShadow(offsetX = 0.px, offsetY = 4.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1))
            ) {
                // 検索ボックス - TextInputを使用してonChangeで制御
                TextInput(
                    text = searchQuery,
                    onTextChange = { searchQuery = it },
                    placeholder = "組織名で検索",
                    modifier = Modifier.fillMaxWidth()
                )

                // 組織一覧
                Column(verticalArrangement = Arrangement.spacedBy(2.px)) {
                    orgs.filter { org ->
                        searchQuery.isBlank() || org.name.contains(searchQuery, ignoreCase = true)
                    }.forEach { org ->
                        val isSelected = org.id == currentOrgId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.px)
                                .onClick {
                                    onOrgChange(org.id)
                                    localStorage.setItem("selectedOrgId", org.id)
                                    showDropdown = false
                                }
                                .cursor(Cursor.Pointer)
                                .borderRadius(4.px)
                                .let { if (isSelected) it.backgroundColor(rgba(42, 120, 214, 0.1)) else it },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpanText(
                                org.name,
                                modifier = Modifier
                                    .fontSize(FontSize.Small)
                                    .let { if (isSelected) it.fontWeight(FontWeight.Bold) else it }
                            )
                            if (org.domains.isNotEmpty()) {
                                SpanText(
                                    org.domains.joinToString(", "),
                                    modifier = Modifier.fontSize(FontSize.Small).color(Colors.Gray)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
