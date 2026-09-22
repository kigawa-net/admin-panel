package net.kigawa.admin.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * インフラ構成ページ・(旧)サーバー管理ページの両方から使われる、ノード単位の
 * カード表示+操作(Cordon/Drain/シャットダウン/再起動/Pod一覧)。issue #116で
 * サーバー管理画面を廃止しインフラ構成画面へ統合した際に、ここへ切り出した。
 */

internal enum class PendingOperation { REBOOTING, SHUTTING_DOWN }

internal const val PENDING_OPERATION_POLL_INTERVAL_MS = 5000L

internal data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: suspend () -> Unit
)

internal data class PendingShutdownAction(
    val server: ServerStatus,
    val reboot: Boolean
)

/** [ServerCard]の操作コールバックをまとめたもの。呼び出し側(インフラ構成画面)で
 * ノードごとに構築し、そのままカードへ渡す。 */
internal data class ServerCardActions(
    val onCordon: () -> Unit,
    val onUncordon: () -> Unit,
    val onDrain: () -> Unit,
    val onShowPods: () -> Unit,
    val onShutdown: () -> Unit,
    val onReboot: () -> Unit
)

@Composable
internal fun ServerCard(
    server: ServerStatus,
    pendingOperation: PendingOperation?,
    actions: ServerCardActions
) {
    val onCordon = actions.onCordon
    val onUncordon = actions.onUncordon
    val onDrain = actions.onDrain
    val onShowPods = actions.onShowPods
    val onShutdown = actions.onShutdown
    val onReboot = actions.onReboot
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                ReadyBadge(ready = server.ready)
            }
            if (pendingOperation != null) {
                Text(
                    text = when (pendingOperation) {
                        PendingOperation.REBOOTING -> "🔄 再起動中... (自動で状態を確認しています)"
                        PendingOperation.SHUTTING_DOWN -> "⏻ シャットダウン処理中... (自動で状態を確認しています)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(roleLabel(server.role), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("CPU: ${server.cpuCapacity} コア / メモリ: ${formatMemoryCapacity(server.memoryCapacity)}", style = MaterialTheme.typography.bodySmall)
            val cpuUsageText = formatCpuUsage(server.cpuUsageCores, server.cpuCapacity)
            val memoryUsageText = formatMemoryUsage(server.memoryUsageBytes, server.memoryCapacity)
            if (cpuUsageText != null || memoryUsageText != null) {
                Text(
                    "使用中 — CPU: ${cpuUsageText ?: "-"} / メモリ: ${memoryUsageText ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val podText = if (server.podCount != null && server.podCapacity != null) {
                "Pod: ${server.podCount} / ${server.podCapacity}"
            } else {
                "Pod: -"
            }
            Text(podText, style = MaterialTheme.typography.bodySmall)
            Text("kubelet ${server.kubeletVersion} / ${server.osImage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (server.schedulable) "スケジューリング: 有効" else "スケジューリング: 停止中",
                style = MaterialTheme.typography.bodySmall,
                color = if (server.schedulable) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE34948)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val actionsEnabled = pendingOperation == null
                if (server.schedulable) {
                    OutlinedButton(onClick = onCordon, enabled = actionsEnabled) { Text("Cordon") }
                } else {
                    OutlinedButton(onClick = onUncordon, enabled = actionsEnabled) { Text("Uncordon") }
                }
                OutlinedButton(onClick = onDrain, enabled = actionsEnabled) { Text("Drain") }
                OutlinedButton(onClick = onShowPods) { Text("Pod一覧") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onShutdown, enabled = pendingOperation == null) {
                    Text("シャットダウン", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onReboot, enabled = pendingOperation == null) {
                    Text("再起動", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShutdownConfirmDialog(
    server: ServerStatus,
    reboot: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (drainTimeoutSeconds: Int) -> Unit
) {
    var timeoutText by remember { mutableStateOf("60") }
    val actionLabel = if (reboot) "再起動" else "シャットダウン"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${server.name} を${actionLabel}しますか?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cordon・Drainを行った上で、ノードの電源を実際に操作します。元に戻せません。")
                if (server.role == "CONTROL_PLANE") {
                    Text(
                        "⚠ このノードはコントロールプレーンです。${actionLabel}するとクラスタ全体の管理機能に影響する可能性があります。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
                    label = { Text("Pod退避の最大待機時間(秒)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeoutText.toIntOrNull() ?: 60) }) {
                Text("${actionLabel}する", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
internal fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color(0xFF008300) else Color(0xFFE34948)
    val label = if (ready) "Ready" else "NotReady"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color)
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodListDialog(
    server: ServerStatus,
    accessToken: String,
    httpClient: io.ktor.client.HttpClient,
    onDismiss: () -> Unit,
    onRequestDeletePod: (PodSummary) -> Unit
) {
    var pods by remember { mutableStateOf<List<PodSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.id) {
        try {
            pods = fetchPodsOnNode(httpClient, accessToken, server.id).pods
        } catch (e: Exception) {
            error = "Pod一覧を取得できませんでした"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${server.name} のPod一覧") },
        text = {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                pods == null -> CircularProgressIndicator()
                pods!!.isEmpty() -> Text("Podがありません")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pods!!.forEach { pod ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${pod.namespace}/${pod.name}", style = MaterialTheme.typography.bodyMedium)
                                Text(pod.ownerKind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRequestDeletePod(pod) }) {
                                Text("再起動")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}
