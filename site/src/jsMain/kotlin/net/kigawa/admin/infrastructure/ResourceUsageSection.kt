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
                                formatValue = { v -> "${round(v * 10) / 10}%" }
                            )
                            UsageChartCard(
                                title = "$hostName · メモリ",
                                points = series.memGiB,
                                fixedMax = null,
                                color = MEMORY_COLOR,
                                canvasId = "usage-mem-$hostName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 10) / 10} GiB" }
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
                                fixedMax = null,
                                color = CPU_COLOR,
                                canvasId = "usage-kcpu-$nodeName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 100) / 100}コア" }
                            )
                            UsageChartCard(
                                title = "$nodeName · メモリ",
                                points = series.memGiB,
                                fixedMax = null,
                                color = MEMORY_COLOR,
                                canvasId = "usage-kmem-$nodeName-$rangeMinutes",
                                formatValue = { v -> "${round(v * 10) / 10} GiB" }
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

/** 1メトリック分のチャートカード(タイトル+現在値/最大値ラベル+折れ線)。 */
@Composable
private fun UsageChartCard(
    title: String,
    points: List<ResourceUsagePoint>,
    fixedMax: Double?,
    color: String,
    canvasId: String,
    formatValue: (Double) -> String
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(title, modifier = Modifier.fontSize(FontSize.Small).fontWeight(FontWeight.Bold))
            SpanText(
                (current?.let { "現在 ${formatValue(it)}" } ?: "データなし") +
                    (if (max != null && current != null) " / 最大 ${formatValue(max)}" else ""),
                modifier = Modifier.fontSize(FontSize.Small).color(Colors.Gray)
            )
        }
        UsageLineChart(
            canvasId = canvasId,
            points = points,
            color = color,
            fixedMax = fixedMax,
            chartHeight = 72
        )
    }
}

/**
 * Canvas 2Dによるシンプルな折れ線グラフ。NetworkMapCanvasと同じ描画方式(Canvas +
 * CanvasRenderingContext2D)を使い、外部ライブラリに依存しない。
 * グリッド線(25/50/75%)・線の下の薄い塗り・現在値のドットを描画する。
 */
@Composable
private fun UsageLineChart(
    canvasId: String,
    points: List<ResourceUsagePoint>,
    color: String,
    fixedMax: Double?,
    chartHeight: Int
) {
    Canvas(attrs = {
        id(canvasId)
        style {
            width(100.percent)
            height(chartHeight.px)
        }
    })

    LaunchedEffect(points, canvasId, fixedMax, chartHeight) {
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
    }
}