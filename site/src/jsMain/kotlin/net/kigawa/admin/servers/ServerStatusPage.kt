package net.kigawa.admin.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class ServerStatusUiState {
    object Loading : ServerStatusUiState()
    data class Loaded(val servers: List<ServerStatus>) : ServerStatusUiState()
    data class Error(val message: String) : ServerStatusUiState()
}

@Composable
fun ServerStatusPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<ServerStatusUiState>(ServerStatusUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accessToken, refreshKey) {
        state = try {
            ServerStatusUiState.Loaded(fetchServerStatuses(httpClient, accessToken).servers)
        } catch (e: Exception) {
            ServerStatusUiState.Error("サーバー状態を取得できませんでした")
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
                    "サーバー管理",
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
                is ServerStatusUiState.Loading -> SpanText("読み込み中...")
                is ServerStatusUiState.Error -> SpanText(current.message, modifier = Modifier.color(Colors.Red))
                is ServerStatusUiState.Loaded -> current.servers.forEach { server ->
                    ServerCard(
                        server = server,
                        httpClient = httpClient,
                        accessToken = accessToken,
                        onCordon = {
                            if (window.confirm("${server.name} への新規Podのスケジューリングを停止しますか?(既存Podには影響しません)")) {
                                runAction { cordonNode(httpClient, accessToken, server.id) }
                            }
                        },
                        onUncordon = {
                            if (window.confirm("${server.name} への新規Podのスケジューリングを再開しますか?")) {
                                runAction { uncordonNode(httpClient, accessToken, server.id) }
                            }
                        },
                        onDrain = {
                            if (window.confirm("${server.name} 上の全Pod(DaemonSet管理下を除く)を退避しますか?影響範囲が大きい操作です。")) {
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
                        },
                        onDeletePod = { pod ->
                            if (window.confirm("${pod.namespace}/${pod.name} を再起動(削除)しますか?")) {
                                runAction { deletePod(httpClient, accessToken, pod.namespace, pod.name) }
                            }
                        },
                        onShutdown = {
                            promptDrainTimeoutAndConfirm(server, actionLabel = "シャットダウン")?.let { timeout ->
                                runAction { gracefulShutdownNode(httpClient, accessToken, server.id, timeout) }
                            }
                        },
                        onReboot = {
                            promptDrainTimeoutAndConfirm(server, actionLabel = "再起動")?.let { timeout ->
                                runAction { gracefulRebootNode(httpClient, accessToken, server.id, timeout) }
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * タイムアウト値の入力(prompt)→最終確認(confirm)の2段階。どちらかでキャンセルすればnullを返す。
 */
private fun promptDrainTimeoutAndConfirm(server: ServerStatus, actionLabel: String): Int? {
    val input = window.prompt("${server.name} を${actionLabel}します。Pod退避の最大待機時間(秒)を入力してください。", "60")
        ?: return null
    val timeout = input.toIntOrNull() ?: 60
    val warning = if (server.role == "CONTROL_PLANE") {
        "\n⚠ このノードはコントロールプレーンです。${actionLabel}するとクラスタ全体の管理機能に影響する可能性があります。"
    } else {
        ""
    }
    val confirmed = window.confirm(
        "${server.name} を本当に${actionLabel}しますか?Cordon・Drainを行った上で電源を操作します。元に戻せません。$warning"
    )
    return if (confirmed) timeout else null
}

@Composable
private fun ServerCard(
    server: ServerStatus,
    httpClient: HttpClient,
    accessToken: String,
    onCordon: () -> Unit,
    onUncordon: () -> Unit,
    onDrain: () -> Unit,
    onDeletePod: (PodSummary) -> Unit,
    onShutdown: () -> Unit,
    onReboot: () -> Unit
) {
    var showPods by remember { mutableStateOf(false) }
    var pods by remember { mutableStateOf<List<PodSummary>?>(null) }

    LaunchedEffect(showPods, server.id) {
        if (showPods) {
            pods = try {
                fetchPodsOnNode(httpClient, accessToken, server.id).pods
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(4.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(server.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            ReadyBadge(server.ready)
        }
        SpanText(roleLabel(server.role), modifier = Modifier.color(Colors.Gray))
        SpanText("CPU: ${server.cpuCapacity} コア / メモリ: ${formatMemoryCapacity(server.memoryCapacity)}")
        val podText = if (server.podCount != null && server.podCapacity != null) {
            "Pod: ${server.podCount} / ${server.podCapacity}"
        } else {
            "Pod: -"
        }
        SpanText(podText)
        SpanText(
            "kubelet ${server.kubeletVersion} / ${server.osImage}",
            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
        )
        SpanText(
            if (server.schedulable) "スケジューリング: 有効" else "スケジューリング: 停止中",
            modifier = Modifier.color(if (server.schedulable) Colors.Gray else Color("#E34948")).fontSize(FontSize.Small)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.px),
            horizontalArrangement = Arrangement.spacedBy(8.px)
        ) {
            if (server.schedulable) {
                Button(onClick = { onCordon() }) { SpanText("Cordon") }
            } else {
                Button(onClick = { onUncordon() }) { SpanText("Uncordon") }
            }
            Button(onClick = { onDrain() }) { SpanText("Drain") }
            Button(onClick = { showPods = !showPods }) { SpanText(if (showPods) "Podを隠す" else "Pod一覧") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.px),
            horizontalArrangement = Arrangement.spacedBy(8.px)
        ) {
            Button(onClick = { onShutdown() }) {
                SpanText("シャットダウン", modifier = Modifier.color(Color("#E34948")))
            }
            Button(onClick = { onReboot() }) {
                SpanText("再起動", modifier = Modifier.color(Color("#E34948")))
            }
        }

        if (showPods) {
            val currentPods = pods
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.px),
                verticalArrangement = Arrangement.spacedBy(6.px)
            ) {
                when {
                    currentPods == null -> SpanText("読み込み中...")
                    currentPods.isEmpty() -> SpanText("Podがありません")
                    else -> currentPods.forEach { pod ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpanText("${pod.namespace}/${pod.name} (${pod.ownerKind})", modifier = Modifier.fontSize(FontSize.Small))
                            Button(onClick = { onDeletePod(pod) }) { SpanText("再起動") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color("#008300") else Color("#E34948")
    val label = if (ready) "Ready" else "NotReady"
    SpanText(label, modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}
