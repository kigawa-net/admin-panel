package net.kigawa.admin.layout

import androidx.compose.runtime.Composable
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
import com.varabyte.kobweb.silk.components.text.SpanText
import net.kigawa.admin.auth.AuthState
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

/**
 * Google式アカウント切替えメニュー。
 * OrgSwitcherのドロップダウン方式に倣い、有効ユーザー名表示・他アカウントへの切替え・
 * 「別のアカウントを追加」・アカウント毎の削除・フッターの「すべてログアウト」を提供する。
 * 実際のセッション操作は呼び出し側(AppShell経由のKeycloakAuthProvider)に委譲する。
 */
@Composable
fun AccountSwitcher(
    state: AuthState.Authenticated,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAccount: () -> Unit,
    onLogoutAll: () -> Unit
) {
    var showDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(8.px)
            .width(200.px)
    ) {
        // ドロップダウントリガー(有効ユーザー名表示)
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
                SpanText(
                    state.username,
                    modifier = Modifier.fontSize(FontSize.Small).fontWeight(FontWeight.Bold)
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(2.px)) {
                    state.accounts.forEach { account ->
                        val isActive = account.id == state.accountId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.px)
                                .onClick {
                                    if (!isActive) {
                                        onSwitch(account.id)
                                        showDropdown = false
                                    }
                                }
                                .cursor(if (isActive) Cursor.Default else Cursor.Pointer)
                                .borderRadius(4.px)
                                .let { if (isActive) it.backgroundColor(rgba(42, 120, 214, 0.1)) else it },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpanText(
                                account.username,
                                modifier = Modifier
                                    .fontSize(FontSize.Small)
                                    .let { if (isActive) it.fontWeight(FontWeight.Bold) else it }
                            )
                            // アカウント毎の削除(有効アカウント自身も消せる)
                            SpanText(
                                "×",
                                modifier = Modifier
                                    .fontSize(FontSize.Medium)
                                    .color(Colors.Gray)
                                    .cursor(Cursor.Pointer)
                                    .padding(leftRight = 4.px)
                                    .onClick {
                                        onRemove(account.id)
                                        showDropdown = false
                                    }
                            )
                        }
                    }
                }

                // 「別のアカウントを追加」
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.px)
                        .onClick {
                            onAddAccount()
                            showDropdown = false
                        }
                        .cursor(Cursor.Pointer)
                        .borderRadius(4.px),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpanText(
                        "＋ 別のアカウントを追加",
                        modifier = Modifier
                            .fontSize(FontSize.Small)
                            .color(Color("#2A78D6"))
                    )
                }

                // フッター: すべてログアウト
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.px)
                        .onClick {
                            onLogoutAll()
                            showDropdown = false
                        }
                        .cursor(Cursor.Pointer)
                        .borderRadius(4.px),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpanText(
                        "すべてログアウト",
                        modifier = Modifier
                            .fontSize(FontSize.Small)
                            .color(Colors.Gray)
                    )
                }
            }
        }
    }
}
