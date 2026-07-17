package net.kigawa.admin.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.kigawa.admin.auth.createHttpClient

private sealed class ServerStatusUiState {
    object Loading : ServerStatusUiState()
    data class Loaded(val servers: List<ServerStatus>) : ServerStatusUiState()
    data class Error(val message: String) : ServerStatusUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<ServerStatusUiState>(ServerStatusUiState.Loading) }
    val httpClient = remember { createHttpClient() }

    LaunchedEffect(accessToken) {
        state = try {
            ServerStatusUiState.Loaded(fetchServerStatuses(httpClient, accessToken).servers)
        } catch (e: Exception) {
            ServerStatusUiState.Error("サーバー状態を取得できませんでした")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("サーバー管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                is ServerStatusUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is ServerStatusUiState.Error -> Text(
                    text = current.message,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
                is ServerStatusUiState.Loaded -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(current.servers) { server ->
                        ServerCard(server)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: ServerStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                ReadyBadge(ready = server.ready)
            }
            Text(roleLabel(server.role), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("CPU: ${server.cpuCapacity} コア / メモリ: ${formatMemoryCapacity(server.memoryCapacity)}", style = MaterialTheme.typography.bodySmall)
            val podText = if (server.podCount != null && server.podCapacity != null) {
                "Pod: ${server.podCount} / ${server.podCapacity}"
            } else {
                "Pod: -"
            }
            Text(podText, style = MaterialTheme.typography.bodySmall)
            Text("kubelet ${server.kubeletVersion} / ${server.osImage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color(0xFF008300) else Color(0xFFE34948)
    val label = if (ready) "Ready" else "NotReady"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color)
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
