package net.kigawa.admin.users

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.varabyte.kobweb.silk.components.forms.TextInput
import com.varabyte.kobweb.silk.components.text.SpanText
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.common.ErrorStateWithRetry
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class UserListUiState {
    object Loading : UserListUiState()
    data class Loaded(val users: List<KeycloakUser>) : UserListUiState()
    data class Error(val message: String) : UserListUiState()
}

@Composable
fun UserManagementPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<UserListUiState>(UserListUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1)),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                    "ユーザー管理",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
            Button(onClick = { showCreateForm = !showCreateForm }) {
                SpanText(if (showCreateForm) "作成フォームを閉じる" else "ユーザーを作成")
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            statusMessage?.let { message ->
                SpanText(message, modifier = Modifier.color(Colors.Blue))
            }

            if (showCreateForm) {
                CreateUserForm(
                    onCreate = { request ->
                        showCreateForm = false
                        runAction { createUser(httpClient, accessToken, request) }
                    }
                )
            }

            when (val current = state) {
                is UserListUiState.Loading -> SpanText("読み込み中...")
                is UserListUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is UserListUiState.Loaded -> current.users.forEach { user ->
                    UserCard(
                        user = user,
                        onToggleEnabled = {
                            val confirmMessage = if (user.enabled) {
                                "${user.username} を無効化しますか?"
                            } else {
                                "${user.username} を有効化しますか?"
                            }
                            if (window.confirm(confirmMessage)) {
                                if (user.enabled) {
                                    runAction { disableUser(httpClient, accessToken, user.id) }
                                } else {
                                    runAction { enableUser(httpClient, accessToken, user.id) }
                                }
                            }
                        },
                        onResetPassword = { newPassword ->
                            if (window.confirm("${user.username} のパスワードをリセットしますか?")) {
                                runAction {
                                    resetUserPassword(httpClient, accessToken, user.id, ResetPasswordRequest(newPassword, true))
                                }
                            }
                        },
                        onDelete = {
                            if (window.confirm("${user.username} を削除しますか?元に戻せません。")) {
                                runAction { deleteUser(httpClient, accessToken, user.id) }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateUserForm(onCreate: (CreateUserRequest) -> Unit) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var temporaryPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        SpanText("新規ユーザー作成", modifier = Modifier.fontWeight(FontWeight.Bold))
        TextInput(text = username, onTextChange = { username = it }, placeholder = "ユーザー名")
        TextInput(text = email, onTextChange = { email = it }, placeholder = "メールアドレス")
        TextInput(text = firstName, onTextChange = { firstName = it }, placeholder = "名")
        TextInput(text = lastName, onTextChange = { lastName = it }, placeholder = "姓")
        TextInput(text = temporaryPassword, onTextChange = { temporaryPassword = it }, placeholder = "初期パスワード")
        Button(
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
            SpanText("作成する")
        }
    }
}

@Composable
private fun UserCard(
    user: KeycloakUser,
    onToggleEnabled: () -> Unit,
    onResetPassword: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showResetPassword by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }

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
            SpanText(user.username, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            SpanText(
                if (user.enabled) "有効" else "無効",
                modifier = Modifier.color(if (user.enabled) Color("#008300") else Color("#E34948"))
            )
        }
        val name = listOfNotNull(user.lastName, user.firstName).joinToString(" ")
        if (name.isNotBlank()) {
            SpanText(name, modifier = Modifier.color(Colors.Gray))
        }
        if (!user.email.isNullOrBlank()) {
            SpanText(user.email, modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.px),
            horizontalArrangement = Arrangement.spacedBy(8.px)
        ) {
            Button(onClick = { onToggleEnabled() }) {
                SpanText(if (user.enabled) "無効化" else "有効化")
            }
            Button(onClick = { showResetPassword = !showResetPassword }) {
                SpanText("パスワードリセット")
            }
            Button(onClick = { onDelete() }) { SpanText("削除") }
        }

        if (showResetPassword) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.px),
                horizontalArrangement = Arrangement.spacedBy(8.px),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextInput(text = newPassword, onTextChange = { newPassword = it }, placeholder = "新しいパスワード")
                Button(
                    onClick = {
                        onResetPassword(newPassword)
                        showResetPassword = false
                        newPassword = ""
                    }
                ) {
                    SpanText("リセットする")
                }
            }
        }
    }
}
