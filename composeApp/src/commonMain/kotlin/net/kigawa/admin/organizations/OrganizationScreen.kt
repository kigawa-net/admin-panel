package net.kigawa.admin.organizations

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.createHttpClient

private sealed class OrganizationListUiState {
    object Loading : OrganizationListUiState()
    data class Loaded(val organizations: List<Organization>) : OrganizationListUiState()
    data class Error(val message: String) : OrganizationListUiState()
}

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: suspend () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<OrganizationListUiState>(OrganizationListUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedOrg by remember { mutableStateOf<Organization?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val httpClient = remember { createHttpClient() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accessToken, refreshKey) {
        state = try {
            OrganizationListUiState.Loaded(fetchOrganizations(httpClient, accessToken).organizations)
        } catch (e: Exception) {
            OrganizationListUiState.Error("組織一覧を取得できませんでした")
        }
    }

    fun runAction(action: suspend () -> OrganizationActionResult) {
        scope.launch {
            val result = try {
                action()
            } catch (e: Exception) {
                OrganizationActionResult(false, e.message ?: "失敗しました")
            }
            statusMessage = result.message
            refreshKey++
        }
    }

    val currentSelectedOrg = selectedOrg
    if (currentSelectedOrg != null) {
        OrganizationMembersScreen(
            accessToken = accessToken,
            organization = currentSelectedOrg,
            onBack = { selectedOrg = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("組織管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "組織を作成")
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                is OrganizationListUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is OrganizationListUiState.Error -> Text(
                    text = current.message,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
                is OrganizationListUiState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (current.organizations.isEmpty()) {
                        Text(
                            text = "組織はまだありません",
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(current.organizations) { org ->
                            OrganizationCard(
                                organization = org,
                                onManageMembers = { selectedOrg = org },
                                onDelete = {
                                    pendingConfirmation = PendingConfirmation(
                                        title = "組織を削除しますか?",
                                        message = "${org.name} を削除します。元に戻せません。",
                                        onConfirm = { runAction { deleteOrganization(httpClient, accessToken, org.id) } }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            pendingConfirmation?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = { pendingConfirmation = null },
                    title = { Text(confirmation.title) },
                    text = { Text(confirmation.message) },
                    confirmButton = {
                        TextButton(onClick = {
                            val onConfirm = confirmation.onConfirm
                            pendingConfirmation = null
                            scope.launch { onConfirm() }
                        }) {
                            Text("実行する")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingConfirmation = null }) {
                            Text("キャンセル")
                        }
                    }
                )
            }

            if (showCreateDialog) {
                CreateOrganizationDialog(
                    onDismiss = { showCreateDialog = false },
                    onCreate = { request ->
                        showCreateDialog = false
                        runAction { createOrganization(httpClient, accessToken, request) }
                    }
                )
            }
        }
    }
}

@Composable
private fun OrganizationCard(
    organization: Organization,
    onManageMembers: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(organization.name, style = MaterialTheme.typography.titleMedium)
            organization.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val domainNames = organization.domains.joinToString(", ") { it.name }
            if (domainNames.isNotBlank()) {
                Text(domainNames, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onManageMembers) { Text("メンバー管理") }
                OutlinedButton(onClick = onDelete) { Text("削除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateOrganizationDialog(onDismiss: () -> Unit, onCreate: (CreateOrganizationRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("組織を作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("組織名") }, singleLine = true)
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("ドメイン(例: example.com)") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("説明(任意)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        CreateOrganizationRequest(
                            name = name,
                            domain = domain,
                            description = description.ifBlank { null }
                        )
                    )
                },
                enabled = name.isNotBlank() && domain.isNotBlank()
            ) {
                Text("作成する")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

private sealed class OrganizationMembersUiState {
    object Loading : OrganizationMembersUiState()
    data class Loaded(val members: List<OrganizationMember>) : OrganizationMembersUiState()
    data class Error(val message: String) : OrganizationMembersUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizationMembersScreen(accessToken: String, organization: Organization, onBack: () -> Unit) {
    var state by remember { mutableStateOf<OrganizationMembersUiState>(OrganizationMembersUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val httpClient = remember { createHttpClient() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(organization.id, refreshKey) {
        state = try {
            OrganizationMembersUiState.Loaded(fetchOrganizationMembers(httpClient, accessToken, organization.id).members)
        } catch (e: Exception) {
            OrganizationMembersUiState.Error("メンバー一覧を取得できませんでした")
        }
    }

    fun runAction(action: suspend () -> OrganizationActionResult) {
        scope.launch {
            val result = try {
                action()
            } catch (e: Exception) {
                OrganizationActionResult(false, e.message ?: "失敗しました")
            }
            statusMessage = result.message
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${organization.name} のメンバー") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "メンバーを追加")
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                is OrganizationMembersUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is OrganizationMembersUiState.Error -> Text(
                    text = current.message,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
                is OrganizationMembersUiState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (current.members.isEmpty()) {
                        Text(
                            text = "メンバーはまだいません",
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(current.members) { member ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(member.username, style = MaterialTheme.typography.titleMedium)
                                        if (!member.email.isNullOrBlank()) {
                                            Text(
                                                member.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        pendingConfirmation = PendingConfirmation(
                                            title = "メンバーを削除しますか?",
                                            message = "${member.username} を ${organization.name} から削除します。",
                                            onConfirm = {
                                                runAction { removeOrganizationMember(httpClient, accessToken, organization.id, member.id) }
                                            }
                                        )
                                    }) {
                                        Text("削除")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            pendingConfirmation?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = { pendingConfirmation = null },
                    title = { Text(confirmation.title) },
                    text = { Text(confirmation.message) },
                    confirmButton = {
                        TextButton(onClick = {
                            val onConfirm = confirmation.onConfirm
                            pendingConfirmation = null
                            scope.launch { onConfirm() }
                        }) {
                            Text("実行する")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingConfirmation = null }) {
                            Text("キャンセル")
                        }
                    }
                )
            }

            if (showAddDialog) {
                AddMemberDialog(
                    accessToken = accessToken,
                    httpClient = httpClient,
                    onDismiss = { showAddDialog = false },
                    onAdd = { userId ->
                        showAddDialog = false
                        runAction { addOrganizationMember(httpClient, accessToken, organization.id, userId) }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberDialog(
    accessToken: String,
    httpClient: io.ktor.client.HttpClient,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<OrganizationMember>>(emptyList()) }
    var selectedUser by remember { mutableStateOf<OrganizationMember?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("メンバーを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("ユーザー名またはメールアドレスで検索") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        val currentQuery = query
                        if (currentQuery.isNotBlank()) {
                            isSearching = true
                            scope.launch {
                                searchResults = try {
                                    searchKigawaNetUsers(httpClient, accessToken, currentQuery).users
                                } catch (e: Exception) {
                                    emptyList()
                                }
                                isSearching = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !isSearching
                ) {
                    Text(if (isSearching) "検索中..." else "検索")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    searchResults.forEach { user ->
                        val isSelected = selectedUser?.id == user.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${user.username}${user.email?.let { " ($it)" } ?: ""}",
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            TextButton(onClick = { selectedUser = user }) {
                                Text(if (isSelected) "選択中" else "選択")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedUser?.let { onAdd(it.id) } },
                enabled = selectedUser != null
            ) {
                Text("追加する")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
