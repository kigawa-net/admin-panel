package net.kigawa.admin.networkmap

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
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px

@Composable
fun NetworkMapPage(accessToken: String, onBack: () -> Unit) {
    var topology by remember { mutableStateOf(fallbackNetworkTopology()) }
    var selectedDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    LaunchedEffect(accessToken) {
        topology = try {
            fetchNetworkTopology(httpClient, accessToken)
        } catch (e: Exception) {
            fallbackNetworkTopology()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = org.jetbrains.compose.web.css.rgba(0, 0, 0, 0.1)),
            horizontalArrangement = Arrangement.Start,
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
                    "ネットワークマップ",
                    modifier = Modifier
                        .fontSize(FontSize.XLarge)
                        .fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            NetworkMapLegend()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .backgroundColor(Colors.White)
                    .borderRadius(8.px)
                    .padding(8.px)
                    .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = org.jetbrains.compose.web.css.rgba(0, 0, 0, 0.08))
            ) {
                NetworkMapCanvas(
                    topology = topology,
                    selectedDevice = selectedDevice,
                    onSelect = { selectedDevice = it }
                )
            }

            DeviceInfoCard(device = selectedDevice)
        }
    }
}

@Composable
private fun NetworkMapLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.px)
    ) {
        DeviceType.entries.forEach { type ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.px)
            ) {
                com.varabyte.kobweb.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(10.px)
                        .height(10.px)
                        .borderRadius(50.percent)
                        .backgroundColor(org.jetbrains.compose.web.css.Color(colorForType(type)))
                )
                SpanText(type.label, modifier = Modifier.fontSize(FontSize.Small))
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(device: NetworkDevice?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = org.jetbrains.compose.web.css.rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(4.px)
    ) {
        if (device == null) {
            SpanText("機器をクリックすると詳細が表示されます", modifier = Modifier.color(Colors.Gray))
        } else {
            SpanText(device.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            SpanText("種類: ${device.type.label}")
            SpanText("IPアドレス: ${device.ipAddress}")
            SpanText("用途: ${device.purpose}")
        }
    }
}
