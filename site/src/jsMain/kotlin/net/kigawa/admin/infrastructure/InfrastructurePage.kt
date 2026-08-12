package net.kigawa.admin.infrastructure

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
import net.kigawa.admin.common.ErrorStateWithRetry
import net.kigawa.admin.servers.roleLabel
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class InfrastructureUiState {
    object Loading : InfrastructureUiState()
    data class Loaded(val topology: InfrastructureTopology) : InfrastructureUiState()
    data class Error(val message: String) : InfrastructureUiState()
}

@Composable
fun InfrastructurePage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<InfrastructureUiState>(InfrastructureUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    LaunchedEffect(accessToken, refreshKey) {
        state = try {
            InfrastructureUiState.Loaded(fetchInfrastructureTopology(httpClient, accessToken))
        } catch (e: Exception) {
            InfrastructureUiState.Error("インフラ構成を取得できませんでした")
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
                    "インフラ構成",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            when (val current = state) {
                is InfrastructureUiState.Loading -> SpanText("読み込み中...")
                is InfrastructureUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is InfrastructureUiState.Loaded -> if (!current.topology.proxmoxConfigured) {
                    SpanText(
                        "Proxmox連携が設定されていません(PROXMOX_API_TOKEN_ID / PROXMOX_API_TOKEN_SECRET未設定)",
                        modifier = Modifier.color(Colors.Gray)
                    )
                } else {
                    current.topology.hosts.forEach { host -> HostCard(host) }
                    if (current.topology.standaloneNodes.isNotEmpty()) {
                        SpanText(
                            "物理専用ノード(VM化されていないK8sノード)",
                            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium).padding(top = 8.px)
                        )
                        current.topology.standaloneNodes.forEach { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.px)
                                    .backgroundColor(Colors.White)
                                    .borderRadius(8.px)
                                    .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    SpanText(node.name, modifier = Modifier.fontWeight(FontWeight.Bold))
                                    SpanText(roleLabel(node.role), modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
                                }
                                ReadyBadge(node.ready)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostCard(host: InfraHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(6.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(host.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            OnlineBadge(host.online)
        }
        if (host.cpuCores != null || host.memoryBytes != null) {
            SpanText(
                "CPU: ${host.cpuCores ?: "-"} コア / メモリ: ${formatBytesAsGiB(host.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }

        if (host.vms.isEmpty()) {
            SpanText(
                if (host.online) "稼働中のVMはありません" else "オフラインのため不明",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.px),
                verticalArrangement = Arrangement.spacedBy(4.px)
            ) {
                host.vms.forEach { vm -> VmRow(vm) }
            }
        }
    }
}

@Composable
private fun VmRow(vm: InfraVm) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(topBottom = 4.px),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpanText(vm.name, modifier = Modifier.fontSize(FontSize.Small))
                if (vm.matchedNode != null) {
                    SpanText(
                        "  K8sノード",
                        modifier = Modifier.color(Color("#2A78D6")).fontSize(FontSize.Small)
                    )
                }
            }
            SpanText(
                "vmid ${vm.vmid} · ${vm.cpuCores ?: "-"} コア / ${formatBytesAsGiB(vm.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        vm.matchedNode?.let { ReadyBadge(it.ready) }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color("#008300") else Color("#E34948")
    SpanText(if (online) "Online" else "Offline", modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}

@Composable
private fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color("#008300") else Color("#E34948")
    SpanText(if (ready) "Ready" else "NotReady", modifier = Modifier.color(color).fontSize(FontSize.Small))
}
