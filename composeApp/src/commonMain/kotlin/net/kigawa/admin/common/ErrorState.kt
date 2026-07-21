package net.kigawa.admin.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 各画面のエラー状態で共通して使う、メッセージ+リロードボタンの表示。
 * それまで各画面がエラーメッセージのみ表示し、再試行するにはページ全体を再読み込みする
 * しかなかった(kigawa-net/admin-panel#55)。
 */
@Composable
fun ErrorStateWithRetry(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) {
            Text("リロード")
        }
    }
}
