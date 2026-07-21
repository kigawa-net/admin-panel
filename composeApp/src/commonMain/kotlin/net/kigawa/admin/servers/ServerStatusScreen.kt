package net.kigawa.admin.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.createHttpClient
import net.kigawa.admin.common.ErrorStateWithRetry

private sealed class ServerStatusUiState {
    object Loading : ServerStatusUiState()
    data class Loaded(val servers: List<ServerStatus>) : ServerStatusUiState()
    data class Error(val message: String) : ServerStatusUiState()
}

private enum class PendingOperation { REBOOTING, SHUTTING_DOWN }

private const val PENDING_OPERATION_POLL_INTERVAL_MS = 5000L

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: suspend () -> Unit
)

private data class PendingShutdownAction(
    val server: ServerStatus,
    val reboot: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<ServerStatusUiState>(ServerStatusUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var pendingShutdownAction by remember { mutableStateOf<PendingShutdownAction?>(null) }
    var podListNode by remember { mutableStateOf<ServerStatus?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingOperations by remember { mutableStateOf<Map<String, PendingOperation>>(emptyMap()) }
    val httpClient = remember { createHttpClient() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        state = try {
            val servers = fetchServerStatuses(httpClient, accessToken).servers
            val stillPending = mutableMapOf<String, PendingOperation>()
            pendingOperations.forEach { (nodeId, op) ->
                val server = servers.find { it.id == nodeId }
                val resolved = server == null || when (op) {
                    PendingOperation.REBOOTING -> server.ready
                    PendingOperation.SHUTTING_DOWN -> !server.ready
                }
                if (resolved) {
                    if (server != null) {
                        statusMessage = when (op) {
                            PendingOperation.REBOOTING -> "${server.name} の再起動が完了しました"
                            PendingOperation.SHUTTING_DOWN -> "${server.name} のシャットダウンが完了しました"
                        }
                    }
                } else {
                    stillPending[nodeId] = op
                }
            }
            pendingOperations = stillPending
            ServerStatusUiState.Loaded(servers)
        } catch (e: Exception) {
            ServerStatusUiState.Error("サーバー状態を取得できませんでした")
        }
    }

    LaunchedEffect(accessToken, refreshKey) {
        refresh()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PENDING_OPERATION_POLL_INTERVAL_MS)
            if (pendingOperations.isNotEmpty()) {
                refresh()
            }
        }
    }

    fun runAction(action: suspend () -> ActionResult) {
        scope.launch {
            val result = try {
                action()
            } catch (e: Exception) {
                ActionResult(false, e.message ?: "失敗しました")
            }
            statusMessage = result.message
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("サーバー管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                is ServerStatusUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is ServerStatusUiState.Error -> ErrorStateWithRetry(
                    message = current.message,
                    onRetry = { refreshKey++ },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                is ServerStatusUiState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(current.servers) { server ->
                            ServerCard(
                                server = server,
                                pendingOperation = pendingOperations[server.id],
                                onCordon = {
                                    pendingConfirmation = PendingConfirmation(
                                        title = "スケジューリングを停止しますか?",
                                        message = "${server.name} への新規Podのスケジューリングを停止します(既存Podには影響しません)。",
                                        onConfirm = { runAction { cordonNode(httpClient, accessToken, server.id) } }
                                    )
                                },
                                onUncordon = {
                                    pendingConfirmation = PendingConfirmation(
                                        title = "スケジューリングを再開しますか?",
                                        message = "${server.name} への新規Podのスケジューリングを再開します。",
                                        onConfirm = { runAction { uncordonNode(httpClient, accessToken, server.id) } }
                                    )
                                },
                                onDrain = {
                                    pendingConfirmation = PendingConfirmation(
                                        title = "ノードをDrainしますか?",
                                        message = "${server.name} 上の全Pod(DaemonSet管理下を除く)を退避します。影響範囲が大きい操作です。",
                                        onConfirm = {
                                            scope.launch {
                                                val result = try {
                                                    drainNode(httpClient, accessToken, server.id)
                                                } catch (e: Exception) {
                                                    null
                                                }
                                                statusMessage = if (result != null) {
                                                    "Drain完了: 退避${result.evicted}件 / スキップ${result.skipped}件 / 失敗${result.failed}件"
                                                } else {
                                                    "Drainに失敗しました"
                                                }
                                                refreshKey++
                                            }
                                        }
                                    )
                                },
                                onShowPods = { podListNode = server },
                                onShutdown = { pendingShutdownAction = PendingShutdownAction(server, reboot = false) },
                                onReboot = { pendingShutdownAction = PendingShutdownAction(server, reboot = true) }
                            )
                        }
                    }
                }
            }

            pendingConfirmation?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = { pendingConfirmation = null },
                    title = { Text(confirmation.title) },
                    text = { Text(confirmation.message) },
                    confirmButton = {
                        TextButton(onClick = {
                            val onConfirm = confirmation.onConfirm
                            pendingConfirmation = null
                            scope.launch { onConfirm() }
                        }) {
                            Text("実行する")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingConfirmation = null }) {
                            Text("キャンセル")
                        }
                    }
                )
            }

            podListNode?.let { node ->
                PodListDialog(
                    server = node,
                    accessToken = accessToken,
                    httpClient = httpClient,
                    onDismiss = { podListNode = null },
                    onRequestDeletePod = { pod ->
                        pendingConfirmation = PendingConfirmation(
                            title = "Podを再起動しますか?",
                            message = "${pod.namespace}/${pod.name} を削除します(所有者があれば自動的に再作成されます)。",
                            onConfirm = {
                                runAction { deletePod(httpClient, accessToken, pod.namespace, pod.name) }
                                podListNode = null
                            }
                        )
                    }
                )
            }

            pendingShutdownAction?.let { action ->
                ShutdownConfirmDialog(
                    server = action.server,
                    reboot = action.reboot,
                    onDismiss = { pendingShutdownAction = null },
                    onConfirm = { timeoutSeconds ->
                        pendingShutdownAction = null
                        val nodeId = action.server.id
                        val operation = if (action.reboot) PendingOperation.REBOOTING else PendingOperation.SHUTTING_DOWN
                        runAction {
                            val result = if (action.reboot) {
                                gracefulRebootNode(httpClient, accessToken, nodeId, timeoutSeconds)
                            } else {
                                gracefulShutdownNode(httpClient, accessToken, nodeId, timeoutSeconds)
                            }
                            if (result.success) {
                                pendingOperations = pendingOperations + (nodeId to operation)
                            }
                            result
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: ServerStatus,
    pendingOperation: PendingOperation?,
    onCordon: () -> Unit,
    onUncordon: () -> Unit,
    onDrain: () -> Unit,
    onShowPods: () -> Unit,
    onShutdown: () -> Unit,
    onReboot: () -> Unit
) {
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
private fun ShutdownConfirmDialog(
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
private fun ReadyBadge(ready: Boolean) {
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
private fun PodListDialog(
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
