package net.kigawa.admin.traffic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import net.kigawa.admin.auth.createHttpClient
import net.kigawa.admin.common.ErrorStateWithRetry

private sealed class TrafficUiState {
    object Loading : TrafficUiState()
    data class Loaded(val response: TrafficResponse) : TrafficUiState()
    data class Error(val message: String) : TrafficUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<TrafficUiState>(TrafficUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    val httpClient = remember { createHttpClient() }

    LaunchedEffect(accessToken, refreshKey) {
        state = try {
            TrafficUiState.Loaded(fetchTraffic(httpClient, accessToken))
        } catch (e: Exception) {
            TrafficUiState.Error(e.message ?: "トラフィックデータの取得に失敗しました")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ネットワークトラフィック") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            when (val current = state) {
                is TrafficUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is TrafficUiState.Error -> ErrorStateWithRetry(
                    message = current.message,
                    onRetry = { refreshKey++ }
                )
                is TrafficUiState.Loaded -> {
                    Card(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "WireGuard ネットワーク帯域(直近${current.response.rangeMinutes}分)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TrafficLegend()
                            if (current.response.series.isEmpty()) {
                                Text(
                                    text = "データがありません",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                TrafficChart(series = current.response.series)
                                Text(
                                    text = "グラフをタップすると各時点の値を表示します",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
