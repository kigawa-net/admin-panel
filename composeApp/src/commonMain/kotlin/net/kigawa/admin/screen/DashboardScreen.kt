package net.kigawa.admin.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    username: String,
    isAdmin: Boolean,
    onLogout: () -> Unit,
    onOpenNetworkMap: () -> Unit,
    onOpenTraffic: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenOrganizations: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Panel") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User"
                        )
                        Text(username)
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Logout"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "Welcome back, $username!",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DashboardCard(
                    title = "Users",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )
                DashboardCard(
                    title = "Sessions",
                    value = "1",
                    modifier = Modifier.weight(1f)
                )
                DashboardCard(
                    title = "Roles",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )
            }

            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenNetworkMap() }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ネットワークマップ",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "kigawa-net の機器構成を図で確認する",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenTraffic() }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ネットワークトラフィック",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Prometheus から取得した帯域を確認する",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isAdmin) {
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenServers() }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "サーバー管理",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "各ノードの稼働状態を確認・操作する",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenUsers() }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ユーザー管理",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Keycloakユーザーの作成・削除・パスワードリセットを行う",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenOrganizations() }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "組織管理",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "組織の作成・削除とメンバー管理を行う",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
