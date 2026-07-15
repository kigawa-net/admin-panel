package net.kigawa.admin.traffic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Fixed categorical order (palette slots 1 and 2) — never reassigned per series identity.
private val RxColorLight = Color(0xFF2A78D6)
private val RxColorDark = Color(0xFF3987E5)
private val TxColor = Color(0xFF008300)

@Composable
private fun rxColor(): Color = if (isSystemInDarkTheme()) RxColorDark else RxColorLight

@Composable
fun TrafficLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LegendEntry(color = rxColor(), label = "受信 (Rx)")
        LegendEntry(color = TxColor, label = "送信 (Tx)")
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TrafficChart(series: List<TrafficPoint>, modifier: Modifier = Modifier) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val rx = rxColor()

    val maxValue = (series.maxOfOrNull { maxOf(it.rxBitsPerSecond, it.txBitsPerSecond) } ?: 0.0).coerceAtLeast(1.0)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        if (series.isEmpty()) return@detectTapGestures
                        val stepX = size.width.toFloat() / (series.size - 1).coerceAtLeast(1)
                        val index = (offset.x / stepX).roundToInt().coerceIn(0, series.lastIndex)
                        selectedIndex = index
                    }
                }
        ) {
            if (series.isEmpty()) return@Canvas

            val chartWidth = size.width
            val chartHeight = size.height
            val stepX = chartWidth / (series.size - 1).coerceAtLeast(1)

            fun yFor(value: Double): Float =
                chartHeight - (value / maxValue).toFloat().coerceIn(0f, 1f) * chartHeight

            // Recessive hairline gridlines at 0%, 50%, 100% of the value range.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = chartHeight - fraction * chartHeight
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1f
                )
            }

            fun drawSeries(values: List<Double>, color: Color) {
                if (values.size < 2) return
                for (i in 0 until values.lastIndex) {
                    drawLine(
                        color = color,
                        start = Offset(i * stepX, yFor(values[i])),
                        end = Offset((i + 1) * stepX, yFor(values[i + 1])),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                val lastX = values.lastIndex * stepX
                val lastY = yFor(values.last())
                drawCircle(color = surfaceColor, radius = 6.dp.toPx(), center = Offset(lastX, lastY))
                drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
            }

            drawSeries(series.map { it.rxBitsPerSecond }, rx)
            drawSeries(series.map { it.txBitsPerSecond }, TxColor)

            // End-of-line direct labels (sparing — only the last value per series).
            val rxLabel = textMeasurer.measure(formatBitsPerSecond(series.last().rxBitsPerSecond), TextStyle(fontSize = 11.sp, color = axisTextColor))
            drawText(
                textLayoutResult = rxLabel,
                topLeft = Offset(chartWidth - rxLabel.size.width - 4.dp.toPx(), yFor(series.last().rxBitsPerSecond) - rxLabel.size.height - 6.dp.toPx())
            )
            val txLabel = textMeasurer.measure(formatBitsPerSecond(series.last().txBitsPerSecond), TextStyle(fontSize = 11.sp, color = axisTextColor))
            drawText(
                textLayoutResult = txLabel,
                topLeft = Offset(chartWidth - txLabel.size.width - 4.dp.toPx(), yFor(series.last().txBitsPerSecond) + 6.dp.toPx())
            )

            selectedIndex?.let { index ->
                val x = index * stepX
                drawLine(
                    color = axisTextColor,
                    start = Offset(x, 0f),
                    end = Offset(x, chartHeight),
                    strokeWidth = 1f
                )
            }
        }

        selectedIndex?.let { index ->
            series.getOrNull(index)?.let { point ->
                TrafficTooltip(
                    point = point,
                    rxColor = rx,
                    txColor = TxColor,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TrafficTooltip(point: TrafficPoint, rxColor: Color, txColor: Color, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Rx ${formatBitsPerSecond(point.rxBitsPerSecond)}", style = MaterialTheme.typography.labelSmall, color = rxColor)
            Text("Tx ${formatBitsPerSecond(point.txBitsPerSecond)}", style = MaterialTheme.typography.labelSmall, color = txColor)
        }
    }
}
