package net.kigawa.admin.networkmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot

private fun colorForType(type: DeviceType): Int = when (type) {
    DeviceType.INTERNET -> Color.rgb(0x60, 0x7D, 0x8B)
    DeviceType.ROUTER -> Color.rgb(0x1E, 0x88, 0xE5)
    DeviceType.SERVER -> Color.rgb(0x43, 0xA0, 0x47)
    DeviceType.PC -> Color.rgb(0xFB, 0x8C, 0x00)
}

class NetworkMapSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var topology: NetworkTopology = NetworkTopology(emptyList(), emptyList())
        set(value) {
            field = value
            render()
        }

    var onDeviceSelected: (NetworkDevice?) -> Unit = {}

    private val density = context.resources.displayMetrics.density
    private val nodeRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, context.resources.displayMetrics)
    private val touchSlopPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, context.resources.displayMetrics)

    private var panX = 0f
    private var panY = 0f
    private var selectedId: String? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x9E, 0x9E, 0x9E)
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
    }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0xEB, 0x3B)
        strokeWidth = 6f * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val backgroundColor = Color.rgb(0xFA, 0xFA, 0xFA)

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        render()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        render()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun nodeCenter(device: NetworkDevice): Pair<Float, Float> {
        return device.x * width to device.y * height
    }

    private fun render() {
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(backgroundColor)
            canvas.save()
            canvas.translate(panX, panY)

            for (connection in topology.connections) {
                val from = topology.devices.find { it.id == connection.fromId } ?: continue
                val to = topology.devices.find { it.id == connection.toId } ?: continue
                val (fx, fy) = nodeCenter(from)
                val (tx, ty) = nodeCenter(to)
                canvas.drawLine(fx, fy, tx, ty, linePaint)
            }

            for (device in topology.devices) {
                val (cx, cy) = nodeCenter(device)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorForType(device.type)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, nodeRadiusPx, paint)
                canvas.drawCircle(cx, cy, nodeRadiusPx, nodeStrokePaint)
                if (device.id == selectedId) {
                    canvas.drawCircle(cx, cy, nodeRadiusPx + 6f * density, selectedStrokePaint)
                }
                canvas.drawText(device.name, cx, cy + nodeRadiusPx + 24f * density, labelPaint)
            }

            canvas.restore()
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!dragged && hypot(event.x - downX, event.y - downY) > touchSlopPx) {
                    dragged = true
                }
                if (dragged) {
                    panX += dx
                    panY += dy
                    render()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragged) {
                    val worldX = event.x - panX
                    val worldY = event.y - panY
                    val tapped = topology.devices.firstOrNull { device ->
                        val (cx, cy) = nodeCenter(device)
                        hypot((worldX - cx).toDouble(), (worldY - cy).toDouble()) <= nodeRadiusPx
                    }
                    selectedId = tapped?.id
                    onDeviceSelected(tapped)
                    render()
                }
                dragged = false
            }
        }
        return true
    }
}
