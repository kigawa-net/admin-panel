package net.kigawa.admin.users

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kigawa.admin.auth.createHttpClient
import net.kigawa.admin.common.ErrorStateWithRetry

private sealed class UserListUiState {
    object Loading : UserListUiState()
    data class Loaded(val users: List<KeycloakUser>) : UserListUiState()
    data class Error(val message: String) : UserListUiState()
}

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: suspend () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<UserListUiState>(UserListUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var resetPasswordUser by remember { mutableStateOf<KeycloakUser?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val httpClient = remember { createHttpClient() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accessToken, refreshKey) {
        state = try {
            UserListUiState.Loaded(fetchUsers(httpClient, accessToken).users)
        } catch (e: Exception) {
            UserListUiState.Error("ユーザー一覧を取得できませんでした")
        }
    }

    fun runAction(action: suspend () -> UserActionResult) {
        scope.launch {
            val result = try {
                action()
            } catch (e: Exception) {
                UserActionResult(false, e.message ?: "失敗しました")
            }
            statusMessage = result.message
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ユーザー管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "ユーザーを作成")
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                is UserListUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is UserListUiState.Error -> ErrorStateWithRetry(
                    message = current.message,
                    onRetry = { refreshKey++ },
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                is UserListUiState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(current.users) { user ->
                            UserCard(
                                user = user,
                                onToggleEnabled = {
                                    pendingConfirmation = if (user.enabled) {
                                        PendingConfirmation(
                                            title = "ユーザーを無効化しますか?",
                                            message = "${user.username} を無効化します。",
                                            onConfirm = { runAction { disableUser(httpClient, accessToken, user.id) } }
                                        )
                                    } else {
                                        PendingConfirmation(
                                            title = "ユーザーを有効化しますか?",
                                            message = "${user.username} を有効化します。",
                                            onConfirm = { runAction { enableUser(httpClient, accessToken, user.id) } }
                                        )
                                    }
                                },
                                onResetPassword = { resetPasswordUser = user },
                                onDelete = {
                                    pendingConfirmation = PendingConfirmation(
                                        title = "ユーザーを削除しますか?",
                                        message = "${user.username} を削除します。元に戻せません。",
                                        onConfirm = { runAction { deleteUser(httpClient, accessToken, user.id) } }
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
                CreateUserDialog(
                    onDismiss = { showCreateDialog = false },
                    onCreate = { request ->
                        showCreateDialog = false
                        runAction { createUser(httpClient, accessToken, request) }
                    }
                )
            }

            resetPasswordUser?.let { user ->
                ResetPasswordDialog(
                    user = user,
                    onDismiss = { resetPasswordUser = null },
                    onReset = { newPassword, temporary ->
                        resetPasswordUser = null
                        runAction {
                            resetUserPassword(httpClient, accessToken, user.id, ResetPasswordRequest(newPassword, temporary))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun UserCard(
    user: KeycloakUser,
    onToggleEnabled: () -> Unit,
    onResetPassword: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(user.username, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (user.enabled) "有効" else "無効",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user.enabled) Color(0xFF008300) else Color(0xFFE34948)
                )
            }
            val name = listOfNotNull(user.lastName, user.firstName).joinToString(" ")
            if (name.isNotBlank()) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!user.email.isNullOrBlank()) {
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onToggleEnabled) {
                    Text(if (user.enabled) "無効化" else "有効化")
                }
                OutlinedButton(onClick = onResetPassword) { Text("パスワードリセット") }
                OutlinedButton(onClick = onDelete) { Text("削除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateUserDialog(onDismiss: () -> Unit, onCreate: (CreateUserRequest) -> Unit) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var temporaryPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ユーザーを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("ユーザー名") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("メールアドレス") }, singleLine = true)
                OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("名") }, singleLine = true)
                OutlinedTextField(value = lastName, onValueChange = { lastName = it }, label = { Text("姓") }, singleLine = true)
                OutlinedTextField(value = temporaryPassword, onValueChange = { temporaryPassword = it }, label = { Text("初期パスワード") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        CreateUserRequest(
                            username = username,
                            email = email.ifBlank { null },
                            firstName = firstName.ifBlank { null },
                            lastName = lastName.ifBlank { null },
                            temporaryPassword = temporaryPassword
                        )
                    )
                },
                enabled = username.isNotBlank() && temporaryPassword.isNotBlank()
            ) {
                Text("作成する")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetPasswordDialog(user: KeycloakUser, onDismiss: () -> Unit, onReset: (String, Boolean) -> Unit) {
    var newPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${user.username} のパスワードをリセット") },
        text = {
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("新しいパスワード(初回ログイン時に変更が必要)") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onReset(newPassword, true) },
                enabled = newPassword.isNotBlank()
            ) {
                Text("リセットする")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
