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
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class ServerStatusUiState {
    object Loading : ServerStatusUiState()
    data class Loaded(val servers: List<ServerStatus>) : ServerStatusUiState()
    data class Error(val message: String) : ServerStatusUiState()
}

@Composable
fun ServerStatusPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<ServerStatusUiState>(ServerStatusUiState.Loading) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    LaunchedEffect(accessToken) {
        state = try {
            ServerStatusUiState.Loaded(fetchServerStatuses(httpClient, accessToken).servers)
        } catch (e: Exception) {
            ServerStatusUiState.Error("サーバー状態を取得できませんでした")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1)),
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
                    "サーバー管理",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            when (val current = state) {
                is ServerStatusUiState.Loading -> SpanText("読み込み中...")
                is ServerStatusUiState.Error -> SpanText(current.message, modifier = Modifier.color(Colors.Red))
                is ServerStatusUiState.Loaded -> current.servers.forEach { server ->
                    ServerCard(server)
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: ServerStatus) {
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
        SpanText(roleLabel(server.role), modifier = Modifier.color(Colors.Gray))
        SpanText("CPU: ${server.cpuCapacity} コア / メモリ: ${formatMemoryCapacity(server.memoryCapacity)}")
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
    }
}

@Composable
private fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color("#008300") else Color("#E34948")
    val label = if (ready) "Ready" else "NotReady"
    SpanText(label, modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}
