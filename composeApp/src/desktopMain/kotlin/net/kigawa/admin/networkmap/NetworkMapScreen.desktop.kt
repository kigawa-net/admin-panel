package net.kigawa.admin.networkmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import net.kigawa.admin.auth.createHttpClient

// Fixed categorical order (dataviz palette slots) — never reassigned per device identity.
private fun colorForType(type: DeviceType): Color = when (type) {
    DeviceType.INTERNET -> Color(0xFF607D8B) // neutral: outside the LAN, not a categorical entity
    DeviceType.ROUTER -> Color(0xFF2A78D6) // slot 1: blue
    DeviceType.CONTROL_PLANE -> Color(0xFF008300) // slot 2: green
    DeviceType.PC -> Color(0xFFE87BA4) // slot 3: magenta
    DeviceType.WORKER -> Color(0xFF1BAF7A) // slot 5: aqua
    DeviceType.GATEWAY -> Color(0xFFEB6834) // slot 6: orange
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun NetworkMapScreen(accessToken: String, onBack: () -> Unit) {
    var selectedDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var topology by remember { mutableStateOf(fallbackNetworkTopology()) }
    val httpClient = remember { createHttpClient() }
    val textMeasurer = rememberTextMeasurer()
    val nodeRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }

    LaunchedEffect(accessToken) {
        topology = try {
            fetchNetworkTopology(httpClient, accessToken)
        } catch (e: Exception) {
            fallbackNetworkTopology()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ネットワークマップ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .pointerInput(topology) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            pan += dragAmount
                        }
                    }
                    .pointerInput(topology, pan) {
                        detectTapGestures { tapOffset ->
                            val worldX = tapOffset.x - pan.x
                            val worldY = tapOffset.y - pan.y
                            val tapped = topology.devices.firstOrNull { device ->
                                val cx = device.x * size.width
                                val cy = device.y * size.height
                                hypot((worldX - cx).toDouble(), (worldY - cy).toDouble()) <= nodeRadiusPx
                            }
                            selectedDevice = tapped
                        }
                    }
            ) {
                fun center(device: NetworkDevice) = Offset(device.x * size.width, device.y * size.height) + pan

                topology.connections.forEach { connection ->
                    val from = topology.devices.find { it.id == connection.fromId }
                    val to = topology.devices.find { it.id == connection.toId }
                    if (from != null && to != null) {
                        drawLine(
                            color = Color(0xFF9E9E9E),
                            start = center(from),
                            end = center(to),
                            strokeWidth = 4f
                        )
                    }
                }

                topology.devices.forEach { device ->
                    val c = center(device)
                    drawCircle(color = colorForType(device.type), radius = nodeRadiusPx, center = c)
                    drawCircle(
                        color = Color.White,
                        radius = nodeRadiusPx,
                        center = c,
                        style = Stroke(width = 3f)
                    )
                    if (device.id == selectedDevice?.id) {
                        drawCircle(
                            color = Color(0xFFFFEB3B),
                            radius = nodeRadiusPx + 6f,
                            center = c,
                            style = Stroke(width = 6f)
                        )
                    }
                    val layout = textMeasurer.measure(device.name, style = TextStyle(fontSize = 11.sp))
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(c.x - layout.size.width / 2, c.y + nodeRadiusPx + 8f)
                    )
                }
            }
            DeviceInfoPanel(device = selectedDevice)
        }
    }
}
