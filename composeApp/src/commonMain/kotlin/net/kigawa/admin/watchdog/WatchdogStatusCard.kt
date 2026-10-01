package net.kigawa.admin.watchdog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import net.kigawa.admin.auth.createHttpClient

private const val WATCHDOG_POLL_INTERVAL_MS = 60_000L

private sealed class WatchdogUiState {
    object Loading : WatchdogUiState()
    object Disabled : WatchdogUiState()
    data class Loaded(val status: WatchdogStatus) : WatchdogUiState()
    object Error : WatchdogUiState()
}

/**
 * k8s-system#220: Alertmanagerの監視パイプライン自体が生きているかを示すカード。
 * 取得に失敗した場合も含め、異常を示す色以外は目立たせない(通常時に注意を引きすぎない
 * ため)。
 */
@Composable
fun WatchdogStatusCard(accessToken: String) {
    var state by remember { mutableStateOf<WatchdogUiState>(WatchdogUiState.Loading) }
    val httpClient: HttpClient = remember { createHttpClient() }

    LaunchedEffect(accessToken) {
        while (true) {
            state = try {
                val status = fetchWatchdogStatus(httpClient, accessToken)
                if (status.configured) WatchdogUiState.Loaded(status) else WatchdogUiState.Disabled
            } catch (e: Exception) {
                WatchdogUiState.Error
            }
            delay(WATCHDOG_POLL_INTERVAL_MS)
        }
    }

    when (val current = state) {
        is WatchdogUiState.Disabled -> Unit // 未設定環境では何も表示しない
        is WatchdogUiState.Loading -> Unit // 初回ロード中は表示しない(チラつき防止)
        is WatchdogUiState.Error -> WatchdogCardContent(
            text = "⚠️ 監視死活(Watchdog)状態の取得に失敗しました",
            isWarning = true
        )
        is WatchdogUiState.Loaded -> {
            val status = current.status
            if (status.isStale) {
                val minutes = (status.secondsSinceLastPing ?: 0) / 60
                WatchdogCardContent(
                    text = if (status.secondsSinceLastPing == null) {
                        "⚠️ 監視死活(Watchdog): pingを一度も受信していません"
                    } else {
                        "⚠️ 監視死活(Watchdog): ${minutes}分間pingがありません。Alertmanager/Prometheusが停止している可能性があります"
                    },
                    isWarning = true
                )
            } else {
                val minutes = (status.secondsSinceLastPing ?: 0) / 60
                WatchdogCardContent(
                    text = "監視死活(Watchdog): 正常(最終ping ${minutes}分前)",
                    isWarning = false
                )
            }
        }
    }
}

@Composable
private fun WatchdogCardContent(text: String, isWarning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isWarning) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
