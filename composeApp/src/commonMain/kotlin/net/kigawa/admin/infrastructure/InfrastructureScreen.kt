package net.kigawa.admin.infrastructure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.kigawa.admin.auth.createHttpClient
import net.kigawa.admin.common.ErrorStateWithRetry
import net.kigawa.admin.servers.roleLabel

private sealed class InfrastructureUiState {
    object Loading : InfrastructureUiState()
    data class Loaded(val topology: InfrastructureTopology) : InfrastructureUiState()
    data class Error(val message: String) : InfrastructureUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfrastructureScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<InfrastructureUiState>(InfrastructureUiState.Loading) }
    // ホスト一覧(/api/infrastructure)より後から、VM/ディスク/PCIの詳細
    // (/api/infrastructure/details)を非同期に読み込む。読み込み中はnullのままにし、
    // 「まだ届いていない」ことと「届いたが空だった」ことを区別する。
    var details by remember { mutableStateOf<InfrastructureDetails?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val httpClient = remember { createHttpClient() }

    // accessTokenはKeycloakのトークン自動更新のたびに新しい値になる。LaunchedEffectの
    // キーにaccessTokenを直接含めると、この画面を開いたままにしているだけでトークン
    // 更新のたびに再取得が走ってしまうため、rememberUpdatedStateで最新値だけを参照し、
    // エフェクト自体はrefreshKeyのみで再実行されるようにする。
    val currentAccessToken by rememberUpdatedState(accessToken)

    LaunchedEffect(refreshKey) {
        details = null
        val topology = try {
            fetchInfrastructureTopology(httpClient, currentAccessToken)
        } catch (e: Exception) {
            state = InfrastructureUiState.Error("インフラ構成を取得できませんでした")
            return@LaunchedEffect
        }
        // まずホスト一覧(高速パス)だけで画面を表示し、続けてVM/ディスク/PCIの詳細を
        // 非同期に読み込む。詳細取得が遅延・失敗してもホスト一覧の表示自体は妨げない。
        state = InfrastructureUiState.Loaded(topology)
        details = try {
            fetchInfrastructureDetails(httpClient, currentAccessToken)
        } catch (e: Exception) {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("インフラ構成") },
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
                is InfrastructureUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is InfrastructureUiState.Error -> ErrorStateWithRetry(
                    message = current.message,
                    onRetry = { refreshKey++ },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                is InfrastructureUiState.Loaded -> if (!current.topology.proxmoxConfigured) {
                    Text(
                        text = "Proxmox連携が設定されていません(PROXMOX_API_TOKEN_ID / PROXMOX_API_TOKEN_SECRET未設定)",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!current.topology.proxmoxReachable) {
                    ErrorStateWithRetry(
                        message = "Proxmoxに接続できませんでした。しばらくしてからもう一度お試しください。",
                        onRetry = { refreshKey++ },
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                } else if (current.topology.hosts.isEmpty() && details != null && details!!.standaloneNodes.isEmpty()) {
                    Text(
                        text = "物理ホスト・ノードが見つかりませんでした",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val currentDetails = details
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(current.topology.hosts) { host ->
                            HostCard(host, currentDetails?.hostDetails?.get(host.name))
                        }
                        if (currentDetails == null) {
                            item {
                                Text(
                                    text = "詳細情報を読み込み中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else if (currentDetails.standaloneNodes.isNotEmpty()) {
                            item {
                                Text(
                                    text = "物理専用ノード(VM化されていないK8sノード)",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            items(currentDetails.standaloneNodes) { node ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(node.name, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                roleLabel(node.role),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            // Proxmox VM化されていない物理ノードにはInfraHostの
                                            // ようなディスク/PCI型番情報は無い(Proxmox管理外の
                                            // ため)が、K8sのNode APIが返すハードウェア/バージョン
                                            // 情報はここで表示できる。
                                            Text(
                                                "CPU: ${node.cpuCapacity} / メモリ: ${node.memoryCapacity}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                "OS: ${node.osImage} / Kubelet: ${node.kubeletVersion}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
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
    }
}

@Composable
private fun HostCard(host: InfraHost, details: InfraHostDetails?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
                OnlineBadge(host.online)
            }
            if (host.cpuCores != null || host.memoryBytes != null) {
                Text(
                    "CPU: ${host.cpuCores ?: "-"} コア / メモリ: ${formatBytesAsGiB(host.memoryBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.cpuModel != null) {
                Text(
                    buildString {
                        append(host.cpuModel)
                        if (host.cpuSockets != null && host.cpuPhysicalCores != null) {
                            append(" (${host.cpuSockets}ソケット × ${host.cpuPhysicalCores}コア)")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.rootfsTotalBytes != null) {
                Text(
                    "ディスク: ${formatBytesAsGiB(host.rootfsUsedBytes)} / ${formatBytesAsGiB(host.rootfsTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (host.pveVersion != null || host.kernelVersion != null) {
                Text(
                    "PVE: ${host.pveVersion ?: "-"} / Kernel: ${host.kernelVersion ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (details == null) {
                Text(
                    "詳細情報を読み込み中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (details.disks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ディスク型番", style = MaterialTheme.typography.labelMedium)
                        details.disks.forEach { disk ->
                            Text(
                                "${disk.model}(${disk.type} / ${formatBytesAsGiB(disk.sizeBytes)}${disk.health?.let { " / $it" } ?: ""})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (details.pciDevices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("拡張デバイス", style = MaterialTheme.typography.labelMedium)
                        details.pciDevices.forEach { device ->
                            Text(
                                "${device.vendor?.let { "$it " } ?: ""}${device.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (details.vms.isEmpty()) {
                    Text(
                        if (host.online) "稼働中のVMはありません" else "オフラインのため不明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        details.vms.forEach { vm -> VmRow(vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VmRow(vm: InfraVm) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.name, style = MaterialTheme.typography.bodyMedium)
                if (vm.matchedNode != null) {
                    Text(
                        "  K8sノード",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "vmid ${vm.vmid} · ${vm.cpuCores ?: "-"} コア / ${formatBytesAsGiB(vm.memoryBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        vm.matchedNode?.let { ReadyBadge(it.ready) }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color(0xFF008300) else Color(0xFFE34948)
    Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun ReadyBadge(ready: Boolean) {
    val color = if (ready) Color(0xFF008300) else Color(0xFFE34948)
    Text(if (ready) "Ready" else "NotReady", style = MaterialTheme.typography.labelSmall, color = color)
}
