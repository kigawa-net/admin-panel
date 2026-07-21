package net.kigawa.admin.common

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px

/**
 * 各画面のエラー状態で共通して使う、メッセージ+リロードボタンの表示。
 * それまで各画面がエラーメッセージのみ表示し、再試行するにはページ全体を再読み込みする
 * しかなかった(kigawa-net/admin-panel#55)。
 */
@Composable
fun ErrorStateWithRetry(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.px)
    ) {
        SpanText(message, modifier = Modifier.color(Color("#E34948")))
        Button(onClick = { onRetry() }) {
            SpanText("リロード")
        }
    }
}
