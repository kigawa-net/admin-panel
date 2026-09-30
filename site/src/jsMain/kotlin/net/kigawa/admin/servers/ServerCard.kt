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
import net.kigawa.admin.infrastructure.DiskUsage
import net.kigawa.admin.infrastructure.HostSlotInventory
import net.kigawa.admin.infrastructure.usagePercentColor
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
    actions: ServerCardActions,
    /** k8sノードのスロット情報。成立時のみ表示し、未取得・VM系では何も出さない。 */
    slots: HostSlotInventory? = null,
    /** マウントポイント別ディスク使用率(admin-panel#148)。未取得時は行自体を出さない。 */
    diskUsage: List<DiskUsage>? = null,
    /** 未完了のカテゴリ集合("slots"/"diskusage")。物理専用ノードの読み込み中にだけ渡す(issue #166)。 */
    pendingCategories: Set<String> = emptySet()
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
        // ディスク使用率(admin-panel#148、SSH+df由来)。スロットと同じ扱いで仮想マシン上の
        // ノードでは抑止する。複数FSはマウントポイント別に見られないため、ルートFS(無ければ
        // 先頭)の1件だけを載せる。
        val diskEntry = if (slots?.virtualized == true) {
            null
        } else {
            diskUsage?.firstOrNull { it.mountpoint == "/" } ?: diskUsage?.firstOrNull()
        }
        if (cpuUsageText != null || memoryUsageText != null || diskEntry != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.px)) {
                SpanText(
                    "使用中 — CPU: ${cpuUsageText ?: "-"} / メモリ: ${memoryUsageText ?: "-"}",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
                if (diskEntry != null) {
                    SpanText(
                        "/ ディスク: ${diskEntry.percent}%" +
                            if (diskEntry.mountpoint != "/") " (${diskEntry.mountpoint})" else "",
                        modifier = Modifier.color(usagePercentColor(diskEntry.percent)).fontSize(FontSize.Small)
                    )
                }
            }
        }
        // ディスク使用率の取得中表示(issue #166)。仮想ノードではそもそも載せないため出さない。
        if (diskEntry == null && slots?.virtualized != true && "diskusage" in pendingCategories) {
            SpanText(
                "ディスク使用率を読み込み中...",
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
        // PCIeデバイス情報(NFD由来)があれば表示
        if (server.pciDevices.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.px),
                verticalArrangement = Arrangement.spacedBy(2.px)
            ) {
                SpanText("PCIeデバイス", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                server.pciDevices.forEach { device ->
                    SpanText(
                        "${device.className} (Vendor: ${device.vendorId.uppercase()})",
                        modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                    )
                }
            }
        }
        SpanText(
            if (server.schedulable) "スケジューリング: 有効" else "スケジューリング: 停止中",
            modifier = Modifier.color(if (server.schedulable) Colors.Gray else Color("#E34948")).fontSize(FontSize.Small)
        )
        // 物理ノードのスロット情報がある場合のみ表示する。VM系ノード・取得済みで非表示の
        // ときは何も出さない。取得中は物理専用ノードのみ読み込み中表示を出す(issue #166)。
        if (slots != null && shouldShowNodeSlots(slots)) {
            SlotInventorySection(slots = slots)
        } else if (slots == null && "slots" in pendingCategories) {
            SpanText(
                "空きスロット情報を読み込み中...",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }

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
