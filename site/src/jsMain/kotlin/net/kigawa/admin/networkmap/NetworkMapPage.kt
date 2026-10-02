package net.kigawa.admin.networkmap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.compose.foundation.layout.Box
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.infrastructure.InfrastructureApiException
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px

/**
 * ネットワークマップの表示状態。
 */
sealed class NetworkMapState {
    /** 読み込み中。 */
    object Loading : NetworkMapState()
    /** 取得成功。 */
    data class Loaded(val topology: NetworkTopology) : NetworkMapState()
    /** 取得失敗。再試行可能。 */
    data class Error(val message: String, val isRetryable: Boolean) : NetworkMapState()
}

@Composable
fun NetworkMapPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<NetworkMapState>(NetworkMapState.Loading) }
    var selectedDevice by remember { mutableStateOf<NetworkDevice?>(null) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    val retryJob = remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(accessToken) {
        retryJob.value?.cancel()
        retryJob.value = launch(Dispatchers.Default) {
            state = NetworkMapState.Loading
            while (true) {
                try {
                    val topology = fetchNetworkTopology(httpClient, accessToken)
                    state = NetworkMapState.Loaded(topology)
                    break
                } catch (e: Exception) {
                    val message = when (e) {
                        is InfrastructureApiException -> e.message ?: "ネットワーク構成を取得できませんでした"
                        else -> "ネットワーク構成を取得できませんでした"
                    }
                    val isRetryable = !(e is InfrastructureApiException && e.statusCode == 401)
                    state = NetworkMapState.Error(message, isRetryable)
                    break
                }
            }
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

            val currentState = state
            when (currentState) {
                is NetworkMapState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(48.px),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.px)
                    ) {
                        com.varabyte.kobweb.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.px)
                                .backgroundColor(Colors.Blue)
                                .borderRadius(50.percent)
                        )
                        SpanText(
                            "ネットワーク構成を読み込み中...",
                            modifier = Modifier.fontSize(FontSize.Medium).color(Colors.Gray)
                        )
                    }
                }

                is NetworkMapState.Loaded -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .backgroundColor(Colors.White)
                        .borderRadius(8.px)
                        .padding(8.px)
                        .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = org.jetbrains.compose.web.css.rgba(0, 0, 0, 0.08))
                ) {
                    NetworkMapCanvas(
                        topology = currentState.topology,
                        selectedDevice = selectedDevice,
                        onSelect = { selectedDevice = it }
                    )
                }

                is NetworkMapState.Error -> Box(
                    modifier = Modifier.fillMaxWidth().padding(48.px),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.px)
                    ) {
                        SpanText(
                            currentState.message ?: "不明なエラー",
                            modifier = Modifier.color(Colors.Red).fontSize(FontSize.Medium)
                        )
                        if (currentState.isRetryable) {
                            Button(onClick = { state = NetworkMapState.Loading }) {
                                SpanText("再試行")
                            }
                        } else {
                            Button(onClick = { state = NetworkMapState.Loading }) {
                                SpanText("再ログイン")
                            }
                        }
                    }
                }
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