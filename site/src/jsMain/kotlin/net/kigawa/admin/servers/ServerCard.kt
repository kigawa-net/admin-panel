package net.kigawa.admin.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.browser.window
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

/**
 * インフラ構成ページ・(旧)サーバー管理ページの両方から使われる、ノード単位の
 * カード表示+操作(Cordon/Drain/シャットダウン/再起動/Pod一覧)。issue #116で
 * サーバー管理ページを廃止しインフラ構成ページへ統合した際に、ここへ切り出した。
 */

internal enum class PendingOperation { REBOOTING, SHUTTING_DOWN }

/**
 * [sawNotReady]はREBOOTINGの完了判定に使う: 開始直後はまだReady=trueのままなので、
 * 「ready==true」だけを完了条件にすると、ノードが実際に落ちる前に即「完了」と
 * 誤判定してしまう。一度NotReadyになったのを確認してからReadyに戻るのを待つ。
 */
internal data class PendingOperationState(val operation: PendingOperation, val sawNotReady: Boolean = false)

internal const val PENDING_OPERATION_POLL_INTERVAL_MS = 5000L

/** [ServerCard]の6つの操作コールバックをまとめたもの。呼び出し側(インフラ構成ページ)で
 * ノードごとに構築し、そのままカードへ渡す。 */
internal data class ServerCardActions(
    val onCordon: () -> Unit,
    val onUncordon: () -> Unit,
    val onDrain: () -> Unit,
    val onDeletePod: (PodSummary) -> Unit,
    val onShutdown: () -> Unit,
    val onReboot: () -> Unit
)

/**
 * タイムアウト値の入力(prompt)→最終確認(confirm)の2段階。どちらかでキャンセルすればnullを返す。
 */
internal fun promptDrainTimeoutAndConfirm(server: ServerStatus, actionLabel: String): Int? {
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
internal fun ServerCard(
    server: ServerStatus,
    pendingOperation: PendingOperation?,
    httpClient: HttpClient,
    accessToken: String,
    actions: ServerCardActions
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
        if (pendingOperation != null) {
            SpanText(
                when (pendingOperation) {
                    PendingOperation.REBOOTING -> "🔄 再起動中... (自動で状態を確認しています)"
                    PendingOperation.SHUTTING_DOWN -> "⏻ シャットダウン処理中... (自動で状態を確認しています)"
                },
                modifier = Modifier.color(Color("#8A6D00")).fontSize(FontSize.Small)
            )
        }
        SpanText(roleLabel(server.role), modifier = Modifier.color(Colors.Gray))
        SpanText("CPU: ${server.cpuCapacity} コア / メモリ: ${formatMemoryCapacity(server.memoryCapacity)}")
        val cpuUsageText = formatCpuUsage(server.cpuUsageCores, server.cpuCapacity)
        val memoryUsageText = formatMemoryUsage(server.memoryUsageBytes, server.memoryCapacity)
        if (cpuUsageText != null || memoryUsageText != null) {
            SpanText(
                "使用中 — CPU: ${cpuUsageText ?: "-"} / メモリ: ${memoryUsageText ?: "-"}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
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
            val actionsEnabled = pendingOperation == null
            if (server.schedulable) {
                Button(onClick = { actions.onCordon() }, enabled = actionsEnabled) { SpanText("Cordon") }
            } else {
                Button(onClick = { actions.onUncordon() }, enabled = actionsEnabled) { SpanText("Uncordon") }
            }
            Button(onClick = { actions.onDrain() }, enabled = actionsEnabled) { SpanText("Drain") }
            Button(onClick = { showPods = !showPods }) { SpanText(if (showPods) "Podを隠す" else "Pod一覧") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.px),
            horizontalArrangement = Arrangement.spacedBy(8.px)
        ) {
            Button(onClick = { actions.onShutdown() }, enabled = pendingOperation == null) {
                SpanText("シャットダウン", modifier = Modifier.color(Color("#E34948")))
            }
            Button(onClick = { actions.onReboot() }, enabled = pendingOperation == null) {
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
                            Button(onClick = { actions.onDeletePod(pod) }) { SpanText("再起動") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color("#008300") else Color("#E34948")
    val label = if (ready) "Ready" else "NotReady"
    SpanText(label, modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}
