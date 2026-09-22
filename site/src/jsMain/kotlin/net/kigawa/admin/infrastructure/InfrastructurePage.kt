package net.kigawa.admin.infrastructure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.common.ErrorStateWithRetry
import net.kigawa.admin.servers.ActionResult
import net.kigawa.admin.servers.PENDING_OPERATION_POLL_INTERVAL_MS
import net.kigawa.admin.servers.PendingOperation
import net.kigawa.admin.servers.PendingOperationState
import net.kigawa.admin.servers.ServerCard
import net.kigawa.admin.servers.ServerCardActions
import net.kigawa.admin.servers.ServerStatus
import net.kigawa.admin.servers.cordonNode
import net.kigawa.admin.servers.deletePod
import net.kigawa.admin.servers.drainNode
import net.kigawa.admin.servers.gracefulRebootNode
import net.kigawa.admin.servers.gracefulShutdownNode
import net.kigawa.admin.servers.promptDrainTimeoutAndConfirm
import net.kigawa.admin.servers.uncordonNode
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class InfrastructureUiState {
    object Loading : InfrastructureUiState()
    data class Loaded(val topology: InfrastructureTopology) : InfrastructureUiState()
    data class Error(val message: String) : InfrastructureUiState()
}

@Composable
fun InfrastructurePage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<InfrastructureUiState>(InfrastructureUiState.Loading) }
    // ホスト一覧(/api/infrastructure)より後から、VM/ディスク/PCIの詳細
    // (/api/infrastructure/details)を非同期に読み込む。読み込み中はnullのままにし、
    // 「まだ届いていない」ことと「届いたが空だった」ことを区別する。
    var details by remember { mutableStateOf<InfrastructureDetails?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    // issue #116でサーバー管理ページを統合した際に持ち込んだ状態。Cordon/Drain/
    // シャットダウン/再起動の実行結果メッセージと、実行中の非同期操作の完了待ち追跡。
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingOperations by remember { mutableStateOf<Map<String, PendingOperationState>>(emptyMap()) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    val scope = rememberCoroutineScope()

    // accessTokenはKeycloakのトークン自動更新のたびに(トークン有効期限前の
    // サイレントリフレッシュで)新しい値になる。LaunchedEffectのキーにaccessTokenを
    // 直接含めると、ユーザーがこのページを開いたままにしているだけでトークン更新の
    // たびに再取得が走ってしまい、断続的なProxmox接続エラーの実運用ログで観測された
    // 「約9分間隔でのバースト状の失敗」の原因になっていた。rememberUpdatedStateで
    // 最新のトークン値だけを参照し、エフェクト自体はrefreshKey(初回表示・再試行時)
    // のみで再実行されるようにする。
    val currentAccessToken by rememberUpdatedState(accessToken)

    /** matchedVM・物理専用ノードの両方をまとめた、現在表示中の全K8sノード。pendingOperationsの解決判定に使う。 */
    fun allKnownNodes(currentDetails: InfrastructureDetails?): List<ServerStatus> {
        if (currentDetails == null) return emptyList()
        val matchedFromVms = currentDetails.hostDetails.values.flatMap { it.vms }.mapNotNull { it.matchedNode }
        return currentDetails.standaloneNodes + matchedFromVms
    }

    LaunchedEffect(refreshKey) {
        details = null
        val topology = try {
            fetchInfrastructureTopology(httpClient, currentAccessToken)
        } catch (e: Throwable) {
            // ktor-client-jsがブラウザのfetch()失敗(CORS・オフライン等)を投げる際、
            // Kotlinのcatch (e: Exception)をすり抜けてコルーチンの未捕捉例外ハンドラに
            // まで届き、ページ全体の描画が白紙になる不具合が実機で確認された。
            // Throwableで受けることで、この描画クラッシュを防ぐ。
            state = InfrastructureUiState.Error("インフラ構成を取得できませんでした")
            return@LaunchedEffect
        }
        // まずホスト一覧(高速パス)だけで画面を表示し、続けてVM/ディスク/PCIの詳細を
        // 非同期に読み込む。詳細取得が遅延・失敗してもホスト一覧の表示自体は妨げない。
        state = InfrastructureUiState.Loaded(topology)
        val fetchedDetails = try {
            fetchInfrastructureDetails(httpClient, currentAccessToken)
        } catch (e: Throwable) {
            null
        }
        details = fetchedDetails

        if (fetchedDetails != null) {
            val nodes = allKnownNodes(fetchedDetails)
            val stillPending = mutableMapOf<String, PendingOperationState>()
            pendingOperations.forEach { (nodeId, opState) ->
                val node = nodes.find { it.id == nodeId }
                if (node == null) {
                    // ノード自体が消えた(一覧から見えなくなった)場合はこれ以上追跡できない
                    return@forEach
                }
                val sawNotReady = opState.sawNotReady || !node.ready
                val resolved = when (opState.operation) {
                    // 開始直後はまだReady=trueのままなので、一度NotReadyを確認してからでないと
                    // 「完了」とみなさない(でなければ落ちる前に完了扱いになってしまう)
                    PendingOperation.REBOOTING -> sawNotReady && node.ready
                    PendingOperation.SHUTTING_DOWN -> !node.ready
                }
                if (resolved) {
                    statusMessage = when (opState.operation) {
                        PendingOperation.REBOOTING -> "${node.name} の再起動が完了しました"
                        PendingOperation.SHUTTING_DOWN -> "${node.name} のシャットダウンが完了しました"
                    }
                } else {
                    stillPending[nodeId] = opState.copy(sawNotReady = sawNotReady)
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
            if (window.confirm("${node.name} への新規Podのスケジューリングを停止しますか?(既存Podには影響しません)")) {
                runAction { cordonNode(httpClient, accessToken, node.id) }
            }
        },
        onUncordon = {
            if (window.confirm("${node.name} への新規Podのスケジューリングを再開しますか?")) {
                runAction { uncordonNode(httpClient, accessToken, node.id) }
            }
        },
        onDrain = {
            if (window.confirm("${node.name} 上の全Pod(DaemonSet管理下を除く)を退避しますか?影響範囲が大きい操作です。")) {
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
        },
        onDeletePod = { pod ->
            if (window.confirm("${pod.namespace}/${pod.name} を再起動(削除)しますか?")) {
                runAction { deletePod(httpClient, accessToken, pod.namespace, pod.name) }
            }
        },
        onShutdown = {
            promptDrainTimeoutAndConfirm(node, actionLabel = "シャットダウン")?.let { timeout ->
                runAction {
                    val result = gracefulShutdownNode(httpClient, accessToken, node.id, timeout)
                    if (result.success) {
                        pendingOperations = pendingOperations + (node.id to PendingOperationState(PendingOperation.SHUTTING_DOWN))
                    }
                    result
                }
            }
        },
        onReboot = {
            promptDrainTimeoutAndConfirm(node, actionLabel = "再起動")?.let { timeout ->
                runAction {
                    val result = gracefulRebootNode(httpClient, accessToken, node.id, timeout)
                    if (result.success) {
                        pendingOperations = pendingOperations + (node.id to PendingOperationState(PendingOperation.REBOOTING))
                    }
                    result
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.px)
            ) {
                Button(onClick = { onBack() }) {
                    SpanText("← 戻る")
                }
                SpanText(
                    "インフラ構成",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            statusMessage?.let { message ->
                SpanText(message, modifier = Modifier.color(Colors.Blue))
            }

            when (val current = state) {
                is InfrastructureUiState.Loading -> SpanText("読み込み中...")
                is InfrastructureUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is InfrastructureUiState.Loaded -> if (!current.topology.proxmoxConfigured) {
                    SpanText(
                        "Proxmox連携が設定されていません(PROXMOX_API_TOKEN_ID / PROXMOX_API_TOKEN_SECRET未設定)",
                        modifier = Modifier.color(Colors.Gray)
                    )
                } else if (!current.topology.proxmoxReachable) {
                    ErrorStateWithRetry(
                        "Proxmoxに接続できませんでした。しばらくしてからもう一度お試しください。",
                        onRetry = { refreshKey++ }
                    )
                } else if (current.topology.hosts.isEmpty() && details != null && details!!.standaloneNodes.isEmpty()) {
                    SpanText("物理ホスト・ノードが見つかりませんでした", modifier = Modifier.color(Colors.Gray))
                } else {
                    current.topology.hosts.forEach { host ->
                        HostCard(
                            host = host,
                            details = details?.hostDetails?.get(host.name),
                            pendingOperations = pendingOperations,
                            httpClient = httpClient,
                            accessToken = accessToken,
                            buildActions = ::buildActions
                        )
                    }
                    val currentDetails = details
                    if (currentDetails == null) {
                        SpanText(
                            "詳細情報を読み込み中...",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small).padding(top = 4.px)
                        )
                    } else if (currentDetails.standaloneNodes.isNotEmpty()) {
                        SpanText(
                            "物理専用ノード(VM化されていないK8sノード)",
                            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium).padding(top = 8.px)
                        )
                        currentDetails.standaloneNodes.forEach { node ->
                            ServerCard(
                                server = node,
                                pendingOperation = pendingOperations[node.id]?.operation,
                                httpClient = httpClient,
                                accessToken = accessToken,
                                actions = buildActions(node)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostCard(
    host: InfraHost,
    details: InfraHostDetails?,
    pendingOperations: Map<String, PendingOperationState>,
    httpClient: HttpClient,
    accessToken: String,
    buildActions: (ServerStatus) -> ServerCardActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(6.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(host.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            OnlineBadge(host.online)
        }
        if (host.cpuCores != null || host.memoryBytes != null) {
            SpanText(
                "CPU: ${host.cpuCores ?: "-"} コア / メモリ: ${formatBytesAsGiB(host.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        if (host.cpuModel != null) {
            SpanText(
                buildString {
                    append(host.cpuModel)
                    if (host.cpuSockets != null && host.cpuPhysicalCores != null) {
                        append(" (${host.cpuSockets}ソケット × ${host.cpuPhysicalCores}コア)")
                    }
                },
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        if (host.rootfsTotalBytes != null) {
            SpanText(
                "ディスク: ${formatBytesAsGiB(host.rootfsUsedBytes)} / ${formatBytesAsGiB(host.rootfsTotalBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        if (host.pveVersion != null || host.kernelVersion != null) {
            SpanText(
                "PVE: ${host.pveVersion ?: "-"} / Kernel: ${host.kernelVersion ?: "-"}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        if (details == null) {
            SpanText(
                "詳細情報を読み込み中...",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small).padding(top = 4.px)
            )
        } else {
            if (details.disks.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
                    SpanText("ディスク型番", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                    details.disks.forEach { disk ->
                        SpanText(
                            "${disk.model}(${disk.type} / ${formatBytesAsGiB(disk.sizeBytes)}${disk.health?.let { " / $it" } ?: ""})",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                }
            }
            if (details.pciDevices.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
                    SpanText("拡張デバイス", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                    details.pciDevices.forEach { device ->
                        SpanText(
                            "${device.vendor?.let { "$it " } ?: ""}${device.name}",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                }
            }

            if (details.vms.isEmpty()) {
                SpanText(
                    if (host.online) "稼働中のVMはありません" else "オフラインのため不明",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.px),
                    verticalArrangement = Arrangement.spacedBy(8.px)
                ) {
                    details.vms.forEach { vm ->
                        VmSection(
                            vm = vm,
                            pendingOperation = vm.matchedNode?.let { pendingOperations[it.id]?.operation },
                            httpClient = httpClient,
                            accessToken = accessToken,
                            buildActions = buildActions
                        )
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
    httpClient: HttpClient,
    accessToken: String,
    buildActions: (ServerStatus) -> ServerCardActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(topBottom = 4.px),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(vm.name, modifier = Modifier.fontSize(FontSize.Small))
            SpanText(
                "vmid ${vm.vmid} · ${vm.cpuCores ?: "-"} コア / ${formatBytesAsGiB(vm.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        // ProxmoxのVMがK8sノードとして稼働している場合、サーバー管理の全機能
        // (実使用量・Cordon/Drain・シャットダウン/再起動等)をここに直接表示する
        // (issue #116: インフラ構成とサーバー管理の統合)。
        val node = vm.matchedNode
        if (node != null) {
            ServerCard(
                server = node,
                pendingOperation = pendingOperation,
                httpClient = httpClient,
                accessToken = accessToken,
                actions = buildActions(node)
            )
        }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color("#008300") else Color("#E34948")
    SpanText(if (online) "Online" else "Offline", modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}
