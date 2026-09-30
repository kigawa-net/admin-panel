package net.kigawa.admin.infrastructure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.compose.css.Cursor
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.text.SpanText
import io.ktor.client.HttpClient
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Canvas
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.PI
import kotlin.math.round

private val RANGE_OPTIONS = listOf(
    60 to "1時間",
    360 to "6時間",
    1440 to "24時間"
)

private const val CPU_COLOR = "#2A78D6"
private const val MEMORY_COLOR = "#008300"

/**
 * インフラのリソース利用量グラフ(issue #132)。
 * 物理ホスト(Proxmox rrddata)とK8sノード(Prometheus cAdvisor)それぞれのCPU/メモリを、
 * 時間範囲セレクタ(1時間/6時間/24時間)付きで表示する。
 */
@Composable
fun ResourceUsageSection(httpClient: HttpClient, accessToken: String) {
    var rangeMinutes by remember { mutableStateOf(60) }
    var data by remember { mutableStateOf<ResourceUsageResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rangeMinutes) {
        loading = true
        error = null
        try {
            data = fetchResourceUsage(httpClient, accessToken, rangeMinutes)
        } catch (e: Throwable) {
            // ブラウザのfetch()失敗はExceptionをすり抜けて描画クラッシュを起こすことがあるため
            // (InfrastructurePage側と同じ対策)Throwableで受ける。
            error = e.message ?: "取得に失敗しました"
            data = null
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(12.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText("リソース利用量", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            Row(horizontalArrangement = Arrangement.spacedBy(8.px)) {
                RANGE_OPTIONS.forEach { (minutes, label) ->
                    val active = rangeMinutes == minutes
                    SpanText(
                        label,
                        modifier = Modifier
                            .padding(leftRight = 10.px, topBottom = 6.px)
                            .onClick { rangeMinutes = minutes }
                            .cursor(if (active) Cursor.Default else Cursor.Pointer)
                            .borderRadius(6.px)
                            .let { if (active) it.backgroundColor(rgba(42, 120, 214, 0.15)) else it }
                            .color(if (active) Color("#2A78D6") else Colors.Gray)
                            .fontWeight(if (active) FontWeight.Bold else FontWeight.Normal)
                            .fontSize(FontSize.Small)
                    )
                }
            }
        }

        when {
            loading -> SpanText(
                "リソース使用量を読み込み中...",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
            error != null -> SpanText(
                "リソース使用量を取得できませんでした: $error",
                modifier = Modifier.color(Colors.Red).fontSize(FontSize.Small)
            )
            data != null -> {
                val response = data!!
                if (response.physicalHosts.isNotEmpty()) {
                    SpanText(
                        "物理ホスト",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small)
                    )
                    response.physicalHosts.entries.sortedBy { it.key }.forEach { (hostName, series) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.px)
                        ) {
                            UsageChartCard(
                                title = "$hostName · CPU",
                                points = series.cpuPercent,
                                fixedMax = 100.0,
                                color = CPU_COLOR,
                                canvasId = "usage-cpu-$hostName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 10) / 10}%" },
                                // 0〜100%のグラフの母数は常に全コア(=100%)。ラベルにコア数を添える(#167)。
                                capacityValue = 100.0,
                                capacityLabel = series.maxCpuCores?.let { "容量 $it コア" },
                                valueInPercent = true
                            )
                            UsageChartCard(
                                title = "$hostName · メモリ",
                                points = series.memGiB,
                                fixedMax = series.maxMemGiB,
                                color = MEMORY_COLOR,
                                canvasId = "usage-mem-$hostName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 10) / 10} GiB" },
                                capacityValue = series.maxMemGiB,
                                capacityLabel = series.maxMemGiB?.let { "容量 ${round(it * 10) / 10} GiB" }
                            )
                        }
                    }
                }
                if (response.k8sNodes.isNotEmpty()) {
                    SpanText(
                        "K8sノード",
                        modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small).padding(top = 4.px)
                    )
                    response.k8sNodes.entries.sortedBy { it.key }.forEach { (nodeName, series) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.px)
                        ) {
                            UsageChartCard(
                                title = "$nodeName · CPU",
                                points = series.cpuCores,
                                fixedMax = series.cpuCapacityCores?.toDouble(),
                                color = CPU_COLOR,
                                canvasId = "usage-kcpu-$nodeName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 100) / 100}コア" },
                                capacityValue = series.cpuCapacityCores?.toDouble(),
                                capacityLabel = series.cpuCapacityCores?.let { "容量 $it コア" }
                            )
                            UsageChartCard(
                                title = "$nodeName · メモリ",
                                points = series.memGiB,
                                fixedMax = series.memCapacityGiB,
                                color = MEMORY_COLOR,
                                canvasId = "usage-kmem-$nodeName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 10) / 10} GiB" },
                                capacityValue = series.memCapacityGiB,
                                capacityLabel = series.memCapacityGiB?.let { "容量 ${round(it * 10) / 10} GiB" }
                            )
                        }
                    }
                }
                if (response.physicalHosts.isEmpty() && response.k8sNodes.isEmpty()) {
                    SpanText(
                        "リソース使用量データがありません",
                        modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                    )
                }
            }
        }
    }
}

/**
 * 1メトリック分のチャートカード(タイトル+現在値/母数ラベル+折れ線)。
 * 母数(割合の分母)は実測最大ではなくハードウェア容量を使う(#167)。
 * 容量が得られない場合のみ従来通りの実測最大にフォールバックして表示を崩さない。
 *
 * @param capacityValue 容量。ポイントと同じ単位で渡すこと(GiB対GiB、コア対コア)。
 * @param capacityLabel 母数として表示するラベル(例: "容量 8 コア")
 * @param valueInPercent ポイントが既に0〜100%の値かどうか(物理ホストCPU)
 */
@Composable
private fun UsageChartCard(
    title: String,
    points: List<ResourceUsagePoint>,
    fixedMax: Double?,
    color: String,
    canvasId: String,
    formatValue: (Double) -> String,
    capacityValue: Double? = null,
    capacityLabel: String? = null,
    valueInPercent: Boolean = false
) {
    Column(
        modifier = Modifier
            .width(50.percent)
            .padding(8.px)
            .backgroundColor(rgba(0, 0, 0, 0.03))
            .borderRadius(6.px),
        verticalArrangement = Arrangement.spacedBy(4.px)
    ) {
        val max = points.maxOfOrNull { it.value }
        val current = points.lastOrNull()?.value
        // 母数は容量を優先し、容量不明のときだけ従来通りの実測最大を使う(#167)。
        val summary = when {
            current == null -> "データなし"
            // 0〜100%のグラフは元々容量(全コア)が分母なので、%を重ねず容量だけ添える。
            valueInPercent && capacityLabel != null -> "現在 ${formatValue(current)} ($capacityLabel)"
            valueInPercent -> "現在 ${formatValue(current)} (母数 100%)"
            capacityValue != null && capacityValue > 0 -> {
                val percent = round(current / capacityValue * 100).toInt()
                "現在 ${formatValue(current)} / ${capacityLabel ?: formatValue(capacityValue)} (${percent}%)"
            }
            else -> "現在 ${formatValue(current)}" +
                (max?.let { " / 最大 ${formatValue(it)}" } ?: "")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(title, modifier = Modifier.fontSize(FontSize.Small).fontWeight(FontWeight.Bold))
            SpanText(
                summary,
                modifier = Modifier.fontSize(FontSize.Small).color(Colors.Gray)
            )
        }
        UsageLineChart(
            canvasId = canvasId,
            points = points,
            color = color,
            fixedMax = fixedMax,
            chartHeight = 72,
            // Y軸上限(=母数)が容量のときは、その旨がグラフ上でも分かるように軸ラベルを出す(#167)。
            // 容量不明で自動スケールしている場合は出さない。
            axisTopLabel = capacityLabel ?: if (valueInPercent) "母数 100%" else null
        )
    }
}

/**
 * Canvas 2Dによるシンプルな折れ線グラフ。NetworkMapCanvasと同じ描画方式(Canvas +
 * CanvasRenderingContext2D)を使い、外部ライブラリに依存しない。
 * グリッド線(25/50/75%)・線の下の薄い塗り・現在値のドットを描画する。
 *
 * @param axisTopLabel Y軸上限(母数)のラベル。容量を示す文字列を左上に描く(#167)。
 */
@Composable
private fun UsageLineChart(
    canvasId: String,
    points: List<ResourceUsagePoint>,
    color: String,
    fixedMax: Double?,
    chartHeight: Int,
    axisTopLabel: String? = null
) {
    Canvas(attrs = {
        id(canvasId)
        style {
            width(100.percent)
            height(chartHeight.px)
        }
    })

    LaunchedEffect(points, canvasId, fixedMax, chartHeight, axisTopLabel) {
        val canvas = document.getElementById(canvasId) as? HTMLCanvasElement ?: return@LaunchedEffect
        val dpr = window.devicePixelRatio.coerceAtLeast(1.0)
        val cssWidth = canvas.clientWidth.coerceAtLeast(10)
        val cssHeight = chartHeight
        canvas.width = (cssWidth * dpr).toInt()
        canvas.height = (cssHeight * dpr).toInt()
        val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return@LaunchedEffect
        ctx.scale(dpr, dpr)
        ctx.clearRect(0.0, 0.0, cssWidth.toDouble(), cssHeight.toDouble())

        if (points.isEmpty()) {
            ctx.fillStyle = "#9CA3AF"
            ctx.font = "12px sans-serif"
            // 既定のtextAlign/textBaselineで中央寄せするため、文字幅を測って描画開始位置をずらす。
            val text = "データなし"
            val textWidth = ctx.measureText(text).width
            ctx.fillText(text, (cssWidth - textWidth) / 2.0, cssHeight / 2.0)
            return@LaunchedEffect
        }

        val padLeft = 6.0
        val padRight = 6.0
        val padTop = 6.0
        val padBottom = 6.0
        val plotW = cssWidth - padLeft - padRight
        val plotH = cssHeight - padTop - padBottom
        val values = points.map { it.value }
        val maxY = fixedMax ?: ((values.maxOrNull() ?: 0.0) * 1.1).coerceAtLeast(0.001)
        val minY = if (fixedMax != null) 0.0 else ((values.minOrNull() ?: 0.0) * 0.9).coerceAtLeast(0.0)
        val range = (maxY - minY).coerceAtLeast(0.0001)

        fun xAt(index: Int): Double =
            if (points.size <= 1) padLeft + plotW / 2.0
            else padLeft + plotW * index / (points.size - 1.0)

        fun yAt(v: Double): Double = padTop + plotH * (1.0 - (v - minY) / range)

        // グリッド線(25/50/75%)
        ctx.strokeStyle = "#E5E7EB"
        ctx.lineWidth = 1.0
        for (f in listOf(0.25, 0.5, 0.75)) {
            val y = padTop + plotH * f
            ctx.beginPath()
            ctx.moveTo(padLeft, y)
            ctx.lineTo(cssWidth - padRight, y)
            ctx.stroke()
        }

        // 線の下を薄く塗る
        ctx.globalAlpha = 0.12
        ctx.fillStyle = color
        ctx.beginPath()
        ctx.moveTo(xAt(0), yAt(points[0].value))
        for (i in 1 until points.size) {
            ctx.lineTo(xAt(i), yAt(points[i].value))
        }
        ctx.lineTo(xAt(points.size - 1), cssHeight - padBottom)
        ctx.lineTo(xAt(0), cssHeight - padBottom)
        ctx.closePath()
        ctx.fill()
        ctx.globalAlpha = 1.0

        // 折れ線
        ctx.strokeStyle = color
        ctx.lineWidth = 2.0
        // 既定のlineJoin/lineCap(round相当)で折れ線を描く。textAlign等はDOM型がenumのため
        // 設定せず、必要なら文字幅を測って位置調整する方式にしている。
        ctx.beginPath()
        for (i in points.indices) {
            val x = xAt(i)
            val y = yAt(points[i].value)
            if (i == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y)
        }
        ctx.stroke()

        // 最後の点(現在値)を強調
        val last = points.last()
        ctx.fillStyle = color
        ctx.beginPath()
        ctx.arc(xAt(points.size - 1), yAt(last.value), 3.0, 0.0, PI * 2.0)
        ctx.fill()

        // Y軸上限(母数=ハードウェア容量)のラベルを左上に描く(#167)。折れ線より後ろだと
        // 見えなくなるため最後に描き、既定のtextAlign(左寄せ)・textBaseline(下端)のままで
        // 文字幅を触らずに配置している。
        val topLabel = axisTopLabel
        if (topLabel != null) {
            ctx.font = "10px sans-serif"
            ctx.fillStyle = "#6B7280"
            ctx.fillText(topLabel, padLeft, padTop + 8.0)
        }
    }
}

/**
 * グルーピングされたリソース使用量グラフ(issue #147)。
 * role / pciType / physicalHost の3軸でタブ切り替え表示。
 */
@Composable
fun GroupedResourceUsageSection(httpClient: HttpClient, accessToken: String) {
    var rangeMinutes by remember { mutableStateOf(60) }
    var data by remember { mutableStateOf<GroupedResourceUsageResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(0) }  // 0: role, 1: pciType, 2: physicalHost

    val TABS = listOf("役割別" to "byRole", "PCIe別" to "byPciType", "物理ホスト別" to "byPhysicalHost")

    LaunchedEffect(rangeMinutes) {
        loading = true
        error = null
        try {
            data = fetchGroupedResourceUsage(httpClient, accessToken, rangeMinutes)
        } catch (e: Throwable) {
            error = e.message ?: "取得に失敗しました"
            data = null
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(12.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText("ノードグループ別リソース使用量", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            Row(horizontalArrangement = Arrangement.spacedBy(4.px)) {
                RANGE_OPTIONS.forEach { (minutes, label) ->
                    val active = rangeMinutes == minutes
                    SpanText(
                        label,
                        modifier = Modifier
                            .padding(leftRight = 10.px, topBottom = 6.px)
                            .onClick { rangeMinutes = minutes }
                            .cursor(if (active) Cursor.Default else Cursor.Pointer)
                            .borderRadius(6.px)
                            .let { if (active) it.backgroundColor(rgba(42, 120, 214, 0.15)) else it }
                            .color(if (active) Color("#2A78D6") else Colors.Gray)
                            .fontWeight(if (active) FontWeight.Bold else FontWeight.Normal)
                            .fontSize(FontSize.Small)
                    )
                }
            }
        }

        // タブ選択
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.px),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { index, (label, _) ->
                val active = activeTab == index
                SpanText(
                    label,
                    modifier = Modifier
                        .padding(leftRight = 16.px, topBottom = 8.px)
                        .onClick { activeTab = index }
                        .cursor(if (active) Cursor.Default else Cursor.Pointer)
                        .borderRadius(6.px)
                        .let { if (active) it.backgroundColor(rgba(42, 120, 214, 0.15)) else it }
                        .color(if (active) Color("#2A78D6") else Colors.Gray)
                        .fontWeight(if (active) FontWeight.Bold else FontWeight.Normal)
                        .fontSize(FontSize.Small)
                )
            }
        }

        when {
            loading -> SpanText(
                "グループ別メトリクスを読み込み中...",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
            error != null -> SpanText(
                "取得に失敗しました: $error",
                modifier = Modifier.color(Colors.Red).fontSize(FontSize.Small)
            )
            data != null -> {
                val groups = when (activeTab) {
                    0 -> data!!.byRole
                    1 -> data!!.byPciType
                    2 -> data!!.byPhysicalHost
                    else -> emptyMap()
                }
                if (groups.isEmpty()) {
                    SpanText("表示するデータがありません", modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
                } else {
                    groups.entries.sortedBy { it.key }.forEach { (groupName, series) ->
                        GroupedSeriesCard(
                            groupName = groupName,
                            series = series,
                            groupCount = series.nodeCount
                        )
                    }
                }
            }
        }
    }
}

/** グループ単位のカード(CPU/メモリ2本のグラフ + ノード数表示) */
@Composable
private fun GroupedSeriesCard(
    groupName: String,
    series: GroupedSeries,
    groupCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.px)
            .backgroundColor(rgba(0, 0, 0, 0.03))
            .borderRadius(8.px),
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(groupName, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            SpanText(
                "$groupCount ノード",
                modifier = Modifier.fontSize(FontSize.Small).color(Colors.Gray)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.px)
        ) {
            UsageChartCard(
                title = "CPU",
                points = series.cpuCores,
                fixedMax = series.cpuCapacityCores,
                color = CPU_COLOR,
                canvasId = "grouped-cpu-${groupName}-${kotlin.random.Random.nextLong()}",
                formatValue = { v -> "${kotlin.math.round(v * 100) / 100}コア" },
                capacityValue = series.cpuCapacityCores,
                capacityLabel = series.cpuCapacityCores?.let { "容量 ${kotlin.math.round(it * 100) / 100}コア" }
            )
            UsageChartCard(
                title = "メモリ",
                points = series.memGiB,
                fixedMax = series.memCapacityGiB,
                color = MEMORY_COLOR,
                canvasId = "grouped-mem-${groupName}-${kotlin.random.Random.nextLong()}",
                formatValue = { v -> "${kotlin.math.round(v * 10) / 10} GiB" },
                capacityValue = series.memCapacityGiB,
                capacityLabel = series.memCapacityGiB?.let { "容量 ${kotlin.math.round(it * 10) / 10} GiB" }
            )
        }
    }
}