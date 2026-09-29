package net.kigawa.admin.networkmap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.dom.Canvas
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.PI
import kotlin.math.hypot

private const val CANVAS_ID = "network-map-canvas"
private const val NODE_RADIUS = 20.0

/** WireGuard等のトンネルインターフェイスとみなすキーワード */
fun getTunnelInterfaceKeywords(): List<String> = listOf("wg", "wireguard", "tun", "vxlan", "gre")

fun colorForType(type: DeviceType): String = when (type) {
    DeviceType.INTERNET -> "#607D8B"
    DeviceType.ROUTER -> "#2A78D6"
    DeviceType.CONTROL_PLANE -> "#008300"
    DeviceType.PC -> "#E87BA4"
    DeviceType.WORKER -> "#1BAF7A"
    DeviceType.GATEWAY -> "#EB6834"
}

/** 接続がトンネル(WireGuard等)かどうか判定 */
fun isTunnelConnection(connection: NetworkConnection): Boolean {
    val iface = connection.`interface`?.lowercase() ?: return false
    return getTunnelInterfaceKeywords().any { iface.contains(it) }
}

/** トンネル接続用の破線パターン */
private val TUNNEL_DASH = doubleArrayOf(6.0, 3.0)
/** トンネル線用の破線パターン(凡例以外) */
private val TUNNEL_LINE_DASH = doubleArrayOf(8.0, 4.0)
/** 空の破線パターン(実線) */
private val EMPTY_DASH = doubleArrayOf(0.0)

/** トンネル接続用の色(青紫系) */
private val TUNNEL_COLOR = "#7C4DFF"
/** 通常接続用の色(グレー) */
private val REGULAR_COLOR = "#9E9E9E"

/** 角丸矩形を手動で描画(Kotlin/JSではroundRect未対応のため) */
fun drawRoundRect(ctx: CanvasRenderingContext2D, x: Double, y: Double, w: Double, h: Double, r: Double) {
    ctx.beginPath()
    ctx.moveTo(x + r, y)
    ctx.lineTo(x + w - r, y)
    ctx.arcTo(x + w, y, x + w, y + r, r)
    ctx.lineTo(x + w, y + h - r)
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r)
    ctx.lineTo(x + r, y + h)
    ctx.arcTo(x, y + h, x, y + h - r, r)
    ctx.lineTo(x, y + r)
    ctx.arcTo(x, y, x + r, y, r)
    ctx.closePath()
}

/** 右下に接続種別の凡例を描画 */
fun drawLegend(ctx: CanvasRenderingContext2D, width: Double, height: Double) {
    val legendX = width - 160.0
    val legendY = height - 70.0
    val boxW = 140.0
    val boxH = 55.0

    // 背景(角丸矩形を手動で描画)
    ctx.fillStyle = "rgba(255,255,255,0.9)"
    ctx.strokeStyle = "#E0E0E0"
    ctx.lineWidth = 1.0
    drawRoundRect(ctx, legendX, legendY, boxW, boxH, 6.0)
    ctx.fill()
    ctx.stroke()

    // 通常接続
    ctx.strokeStyle = REGULAR_COLOR
    ctx.lineWidth = 2.0
    ctx.beginPath()
    ctx.moveTo(legendX + 12.0, legendY + 18.0)
    ctx.lineTo(legendX + 42.0, legendY + 18.0)
    ctx.stroke()
    ctx.fillStyle = "#212121"
    ctx.font = "11px sans-serif"
    ctx.asDynamic().textAlign = "left"
    ctx.fillText("通常接続", legendX + 50.0, legendY + 21.0)

    // トンネル接続
    ctx.strokeStyle = TUNNEL_COLOR
    ctx.lineWidth = 2.5
    ctx.asDynamic().setLineDash(TUNNEL_DASH)
    ctx.beginPath()
    ctx.moveTo(legendX + 12.0, legendY + 38.0)
    ctx.lineTo(legendX + 42.0, legendY + 38.0)
    ctx.stroke()
    ctx.asDynamic().setLineDash(EMPTY_DASH)
    ctx.fillStyle = TUNNEL_COLOR
    ctx.font = "bold 11px sans-serif"
    ctx.fillText("WG", legendX + 44.0, legendY + 41.0)
    ctx.fillStyle = "#212121"
    ctx.font = "11px sans-serif"
    ctx.fillText("WireGuard トンネル", legendX + 50.0, legendY + 41.0)
}

@Composable
fun NetworkMapCanvas(
    topology: NetworkTopology,
    selectedDevice: NetworkDevice?,
    onSelect: (NetworkDevice?) -> Unit
) {
    var panX by remember { mutableStateOf(0.0) }
    var panY by remember { mutableStateOf(0.0) }
    var dragStartX by remember { mutableStateOf<Double?>(null) }
    var dragStartY by remember { mutableStateOf<Double?>(null) }
    var dragMoved by remember { mutableStateOf(false) }

    fun deviceCenter(canvas: HTMLCanvasElement, device: NetworkDevice): Pair<Double, Double> {
        val x = device.x * canvas.clientWidth + panX
        val y = device.y * canvas.clientHeight + panY
        return x to y
    }

    fun redraw() {
        val canvas = document.getElementById(CANVAS_ID) as? HTMLCanvasElement ?: return
        val width = canvas.clientWidth.toDouble()
        val height = canvas.clientHeight.toDouble()
        if (canvas.width != canvas.clientWidth) canvas.width = canvas.clientWidth
        if (canvas.height != canvas.clientHeight) canvas.height = canvas.clientHeight

        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.clearRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())

        // 接続線を描画(トンネルと通常でスタイルを分ける)
        topology.connections.forEach { connection ->
            val from = topology.devices.find { it.id == connection.fromId }
            val to = topology.devices.find { it.id == connection.toId }
            if (from != null && to != null) {
                val (fx, fy) = deviceCenter(canvas, from)
                val (tx, ty) = deviceCenter(canvas, to)
                val isTunnel = isTunnelConnection(connection)
                ctx.beginPath()
                ctx.moveTo(fx, fy)
                ctx.lineTo(tx, ty)
                if (isTunnel) {
                    ctx.strokeStyle = TUNNEL_COLOR
                    ctx.lineWidth = 2.5
                    // 破線でトンネルを表現
                    ctx.asDynamic().setLineDash(TUNNEL_LINE_DASH)
                } else {
                    ctx.strokeStyle = REGULAR_COLOR
                    ctx.lineWidth = 2.0
                    ctx.asDynamic().setLineDash(EMPTY_DASH)
                }
                ctx.stroke()
                ctx.asDynamic().setLineDash(EMPTY_DASH)

                // トンネル接続の場合、中間に「WG」ラベルを表示
                if (isTunnel) {
                    val mx = (fx + tx) / 2.0
                    val my = (fy + ty) / 2.0
                    ctx.fillStyle = TUNNEL_COLOR
                    ctx.font = "bold 11px sans-serif"
                    ctx.asDynamic().textAlign = "center"
                    ctx.fillText("WG", mx, my - 4.0)
                }
            }
        }

        topology.devices.forEach { device ->
            val (x, y) = deviceCenter(canvas, device)

            ctx.beginPath()
            ctx.arc(x, y, NODE_RADIUS, 0.0, 2 * PI)
            ctx.fillStyle = colorForType(device.type)
            ctx.fill()
            ctx.lineWidth = 2.0
            ctx.strokeStyle = "#FFFFFF"
            ctx.stroke()

            if (device.id == selectedDevice?.id) {
                ctx.beginPath()
                ctx.arc(x, y, NODE_RADIUS + 5.0, 0.0, 2 * PI)
                ctx.lineWidth = 3.0
                ctx.strokeStyle = "#FFEB3B"
                ctx.stroke()
            }

            ctx.fillStyle = "#212121"
            ctx.font = "12px sans-serif"
            ctx.asDynamic().textAlign = "center"
            ctx.fillText(device.name, x, y + NODE_RADIUS + 16.0)
        }

        // 凡例(右下)
        drawLegend(ctx, width, height)
    }

    LaunchedEffect(topology, selectedDevice, panX, panY) {
        redraw()
    }

    fun hitTest(offsetX: Double, offsetY: Double): NetworkDevice? {
        val canvas = document.getElementById(CANVAS_ID) as? HTMLCanvasElement ?: return null
        return topology.devices.firstOrNull { device ->
            val (x, y) = deviceCenter(canvas, device)
            hypot(offsetX - x, offsetY - y) <= NODE_RADIUS
        }
    }

    Canvas(attrs = {
        id(CANVAS_ID)
        style {
            width(100.percent)
            height(500.px)
        }
        onMouseDown { event ->
            dragStartX = event.offsetX
            dragStartY = event.offsetY
            dragMoved = false
        }
        onMouseMove { event ->
            val startX = dragStartX
            val startY = dragStartY
            if (startX != null && startY != null) {
                val dx = event.offsetX - startX
                val dy = event.offsetY - startY
                if (dragMoved || hypot(dx, dy) > 3.0) {
                    panX += dx
                    panY += dy
                    dragStartX = event.offsetX
                    dragStartY = event.offsetY
                    dragMoved = true
                }
            }
        }
        onMouseUp { event ->
            if (!dragMoved) {
                onSelect(hitTest(event.offsetX, event.offsetY))
            }
            dragStartX = null
            dragStartY = null
        }
        onMouseLeave {
            dragStartX = null
            dragStartY = null
        }
    })
}