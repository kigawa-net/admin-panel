package net.kigawa.admin.infrastructure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.createHttpClient
import net.kigawa.admin.common.ErrorStateWithRetry
import net.kigawa.admin.servers.ActionResult
import net.kigawa.admin.servers.PENDING_OPERATION_POLL_INTERVAL_MS
import net.kigawa.admin.servers.PendingConfirmation
import net.kigawa.admin.servers.PendingOperation
import net.kigawa.admin.servers.PendingShutdownAction
import net.kigawa.admin.servers.PodListDialog
import net.kigawa.admin.servers.PodSummary
import net.kigawa.admin.servers.ServerCard
import net.kigawa.admin.servers.ServerCardActions
import net.kigawa.admin.servers.ServerStatus
import net.kigawa.admin.servers.ShutdownConfirmDialog
import net.kigawa.admin.servers.cordonNode
import net.kigawa.admin.servers.deletePod
import net.kigawa.admin.servers.drainNode
import net.kigawa.admin.servers.gracefulRebootNode
import net.kigawa.admin.servers.gracefulShutdownNode
import net.kigawa.admin.servers.roleLabel
import net.kigawa.admin.servers.uncordonNode

private sealed class InfrastructureUiState {
    object Loading : InfrastructureUiState()
    data class Loaded(val topology: InfrastructureTopology) : InfrastructureUiState()
    data class Error(val message: String) : InfrastructureUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfrastructureScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<InfrastructureUiState>(InfrastructureUiState.Loading) }
    // ホスト一覧(/api/infrastructure)より後から、VM/ディスク/PCIの詳細
    // (/api/infrastructure/details)を非同期に読み込む。読み込み中はnullのままにし、
    // 「まだ届いていない」ことと「届いたが空だった」ことを区別する。
    var details by remember { mutableStateOf<InfrastructureDetails?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    // issue #116でサーバー管理画面を統合した際に持ち込んだ状態。Cordon/Drain/
    // シャットダウン/再起動の実行結果メッセージと、実行中の非同期操作の完了待ち追跡。
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingOperations by remember { mutableStateOf<Map<String, PendingOperation>>(emptyMap()) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var pendingShutdownAction by remember { mutableStateOf<PendingShutdownAction?>(null) }
    var podListNode by remember { mutableStateOf<ServerStatus?>(null) }
    val httpClient = remember { createHttpClient() }
    val scope = rememberCoroutineScope()

    // accessTokenはKeycloakのトークン自動更新のたびに新しい値になる。LaunchedEffectの
    // キーにaccessTokenを直接含めると、この画面を開いたままにしているだけでトークン
    // 更新のたびに再取得が走ってしまうため、rememberUpdatedStateで最新値だけを参照し、
    // エフェクト自体はrefreshKeyのみで再実行されるようにする。
    val currentAccessToken by rememberUpdatedState(accessToken)

    LaunchedEffect(refreshKey) {
        details = null
        val topology = try {
            fetchInfrastructureTopology(httpClient, currentAccessToken)
        } catch (e: Exception) {
            state = InfrastructureUiState.Error("インフラ構成を取得できませんでした")
            return@LaunchedEffect
        }
        // まずホスト一覧(高速パス)だけで画面を表示し、続けてVM/ディスク/PCIの詳細を
        // 非同期に読み込む。詳細取得が遅延・失敗してもホスト一覧の表示自体は妨げない。
        state = InfrastructureUiState.Loaded(topology)
        val fetchedDetails = try {
            fetchInfrastructureDetails(httpClient, currentAccessToken)
        } catch (e: Exception) {
            null
        }
        details = fetchedDetails

        if (fetchedDetails != null) {
            val nodes = fetchedDetails.standaloneNodes +
                fetchedDetails.hostDetails.values.flatMap { it.vms }.mapNotNull { it.matchedNode }
            val stillPending = mutableMapOf<String, PendingOperation>()
            pendingOperations.forEach { (nodeId, op) ->
                val node = nodes.find { it.id == nodeId }
                val resolved = node == null || when (op) {
                    PendingOperation.REBOOTING -> node.ready
                    PendingOperation.SHUTTING_DOWN -> !node.ready
                }
                if (resolved) {
                    if (node != null) {
                        statusMessage = when (op) {
                            PendingOperation.REBOOTING -> "${node.name} の再起動が完了しました"
                            PendingOperation.SHUTTING_DOWN -> "${node.name} のシャットダウンが完了しました"
                        }
                    }
                } else {
                    stillPending[nodeId] = op
                }
            }
            pendingOperations = stillPending
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PENDING_OPERATION_POLL_INTERVAL_MS)
            if (pendingOperations.isNotEmpty()) {
                refreshKey++
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

    /** ノードごとのCordon/Drain/シャットダウン/再起動操作をまとめて構築する。ProxmoxのVM経由・
     * 物理専用ノードのどちらでも同じロジックで使う(issue #116)。 */
    fun buildActions(node: ServerStatus): ServerCardActions = ServerCardActions(
        onCordon = {
            pendingConfirmation = PendingConfirmation(
                title = "スケジューリングを停止しますか?",
                message = "${node.name} への新規Podのスケジューリングを停止します(既存Podには影響しません)。",
                onConfirm = { runAction { cordonNode(httpClient, accessToken, node.id) } }
            )
        },
        onUncordon = {
            pendingConfirmation = PendingConfirmation(
                title = "スケジューリングを再開しますか?",
                message = "${node.name} への新規Podのスケジューリングを再開します。",
                onConfirm = { runAction { uncordonNode(httpClient, accessToken, node.id) } }
            )
        },
        onDrain = {
            pendingConfirmation = PendingConfirmation(
                title = "ノードをDrainしますか?",
                message = "${node.name} 上の全Pod(DaemonSet管理下を除く)を退避します。影響範囲が大きい操作です。",
                onConfirm = {
                    scope.launch {
                        val result = try {
                            drainNode(httpClient, accessToken, node.id)
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
        onShowPods = { podListNode = node },
        onShutdown = { pendingShutdownAction = PendingShutdownAction(node, reboot = false) },
        onReboot = { pendingShutdownAction = PendingShutdownAction(node, reboot = true) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("インフラ構成") },
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
                is InfrastructureUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is InfrastructureUiState.Error -> ErrorStateWithRetry(
                    message = current.message,
                    onRetry = { refreshKey++ },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                is InfrastructureUiState.Loaded -> if (!current.topology.proxmoxConfigured) {
                    Text(
                        text = "Proxmox連携が設定されていません(PROXMOX_API_TOKEN_ID / PROXMOX_API_TOKEN_SECRET未設定)",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!current.topology.proxmoxReachable) {
                    ErrorStateWithRetry(
                        message = "Proxmoxに接続できませんでした。しばらくしてからもう一度お試しください。",
                        onRetry = { refreshKey++ },
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                } else if (current.topology.hosts.isEmpty() && details != null && details!!.standaloneNodes.isEmpty()) {
                    Text(
                        text = "物理ホスト・ノードが見つかりませんでした",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val currentDetails = details
                    Column(modifier = Modifier.fillMaxSize()) {
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
                            items(current.topology.hosts) { host ->
                                HostCard(
                                    host = host,
                                    details = currentDetails?.hostDetails?.get(host.name),
                                    pendingOperations = pendingOperations,
                                    buildActions = ::buildActions
                                )
                            }
                            if (currentDetails == null) {
                                item {
                                    Text(
                                        text = "詳細情報を読み込み中...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            } else if (currentDetails.standaloneNodes.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "物理専用ノード(VM化されていないK8sノード)",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(currentDetails.standaloneNodes) { node ->
                                    ServerCard(
                                        server = node,
                                        pendingOperation = pendingOperations[node.id],
                                        actions = buildActions(node)
                                    )
                                }
                            }
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
private fun HostCard(
    host: InfraHost,
    details: InfraHostDetails?,
    pendingOperations: Map<String, PendingOperation>,
    buildActions: (ServerStatus) -> ServerCardActions
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
                OnlineBadge(host.online)
            }
            if (host.cpuCores != null || host.memoryBytes != null) {
                Text(
                    "CPU: ${host.cpuCores ?: "-"} コア / メモリ: ${formatBytesAsGiB(host.memoryBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.cpuModel != null) {
                Text(
                    buildString {
                        append(host.cpuModel)
                        if (host.cpuSockets != null && host.cpuPhysicalCores != null) {
                            append(" (${host.cpuSockets}ソケット × ${host.cpuPhysicalCores}コア)")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.rootfsTotalBytes != null) {
                Text(
                    "ディスク: ${formatBytesAsGiB(host.rootfsUsedBytes)} / ${formatBytesAsGiB(host.rootfsTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.pveVersion != null || host.kernelVersion != null) {
                Text(
                    "PVE: ${host.pveVersion ?: "-"} / Kernel: ${host.kernelVersion ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (details == null) {
                Text(
                    "詳細情報を読み込み中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (details.disks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ディスク型番", style = MaterialTheme.typography.labelMedium)
                        details.disks.forEach { disk ->
                            Text(
                                "${disk.model}(${disk.type} / ${formatBytesAsGiB(disk.sizeBytes)}${disk.health?.let { " / $it" } ?: ""})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (details.pciDevices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("拡張デバイス", style = MaterialTheme.typography.labelMedium)
                        details.pciDevices.forEach { device ->
                            Text(
                                "${device.vendor?.let { "$it " } ?: ""}${device.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (details.vms.isEmpty()) {
                    Text(
                        if (host.online) "稼働中のVMはありません" else "オフラインのため不明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.vms.forEach { vm ->
                            VmSection(
                                vm = vm,
                                pendingOperation = vm.matchedNode?.let { pendingOperations[it.id] },
                                buildActions = buildActions
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VmSection(
    vm: InfraVm,
    pendingOperation: PendingOperation?,
    buildActions: (ServerStatus) -> ServerCardActions
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(vm.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "vmid ${vm.vmid} · ${vm.cpuCores ?: "-"} コア / ${formatBytesAsGiB(vm.memoryBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // ProxmoxのVMがK8sノードとして稼働している場合、サーバー管理の全機能
        // (実使用量・Cordon/Drain・シャットダウン/再起動等)をここに直接表示する
        // (issue #116: インフラ構成とサーバー管理の統合)。
        val node = vm.matchedNode
        if (node != null) {
            ServerCard(
                server = node,
                pendingOperation = pendingOperation,
                actions = buildActions(node)
            )
        }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color(0xFF008300) else Color(0xFFE34948)
    Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = color)
}
