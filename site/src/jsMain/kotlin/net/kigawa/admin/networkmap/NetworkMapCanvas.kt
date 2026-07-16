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

fun colorForType(type: DeviceType): String = when (type) {
    DeviceType.INTERNET -> "#607D8B"
    DeviceType.ROUTER -> "#2A78D6"
    DeviceType.CONTROL_PLANE -> "#008300"
    DeviceType.PC -> "#E87BA4"
    DeviceType.WORKER -> "#1BAF7A"
    DeviceType.GATEWAY -> "#EB6834"
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
        val width = canvas.clientWidth
        val height = canvas.clientHeight
        if (canvas.width != width) canvas.width = width
        if (canvas.height != height) canvas.height = height

        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.clearRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())

        ctx.strokeStyle = "#9E9E9E"
        ctx.lineWidth = 2.0
        topology.connections.forEach { connection ->
            val from = topology.devices.find { it.id == connection.fromId }
            val to = topology.devices.find { it.id == connection.toId }
            if (from != null && to != null) {
                val (fx, fy) = deviceCenter(canvas, from)
                val (tx, ty) = deviceCenter(canvas, to)
                ctx.beginPath()
                ctx.moveTo(fx, fy)
                ctx.lineTo(tx, ty)
                ctx.stroke()
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
