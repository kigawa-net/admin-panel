package net.kigawa.admin.githubapp

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
import com.varabyte.kobweb.silk.components.text.SpanText
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.common.ErrorStateWithRetry
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class GithubAppUiState {
    object Loading : GithubAppUiState()
    data class Loaded(val installations: List<GithubInstallation>) : GithubAppUiState()
    data class Error(val message: String) : GithubAppUiState()
}

@Composable
fun GithubAppPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<GithubAppUiState>(GithubAppUiState.Loading) }
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
            GithubAppUiState.Loaded(fetchGithubInstallations(httpClient, accessToken))
        } catch (e: Exception) {
            GithubAppUiState.Error("GitHub Appの情報を取得できませんでした")
        }
    }

    var policyRefreshKey by remember { mutableStateOf(0) }

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
                    "GitHub App",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            when (val current = state) {
                is GithubAppUiState.Loading -> SpanText("読み込み中...")
                is GithubAppUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is GithubAppUiState.Loaded -> if (current.installations.isEmpty()) {
                    SpanText("インストール済みのGitHub Appがありません")
                } else {
                    current.installations.forEach { installation ->
                        InstallationCard(installation = installation, httpClient = httpClient, accessToken = accessToken)
                    }
                }
            }

            CiTokenPolicySection(
                httpClient = httpClient,
                accessToken = accessToken,
                refreshKey = policyRefreshKey,
                onChanged = { policyRefreshKey++ }
            )
        }
    }
}

/**
 * CI向けトークン発行ブローカー(POST /api/github-app/ci-token)の呼び出し元リポジトリ別
 * 許可設定(issue #64)。以前はコードにハードコードされたMapだったが、ここから
 * 追加・編集・削除できるようにした。
 */
@Composable
private fun CiTokenPolicySection(
    httpClient: HttpClient,
    accessToken: String,
    refreshKey: Int,
    onChanged: () -> Unit
) {
    var policies by remember { mutableStateOf<List<CiTokenPolicyEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var editingCallerRepository by remember { mutableStateOf<String?>(null) }
    var callerRepositoryInput by remember { mutableStateOf("") }
    var allowedOwnerInput by remember { mutableStateOf("") }
    var allowedRepositoriesInput by remember { mutableStateOf("") }
    var allowedPermissionsInput by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun resetForm() {
        editingCallerRepository = null
        callerRepositoryInput = ""
        allowedOwnerInput = ""
        allowedRepositoriesInput = ""
        allowedPermissionsInput = ""
        formError = null
    }

    LaunchedEffect(accessToken, refreshKey) {
        policies = try {
            fetchCiTokenPolicies(httpClient, accessToken)
        } catch (e: Exception) {
            loadError = "CI許可設定を取得できませんでした"
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        SpanText(
            "CI向けトークン発行の許可設定",
            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
        )
        SpanText(
            "GitHub ActionsのOIDCトークンでこのブローカー(POST /api/github-app/ci-token)を呼べる" +
                "リポジトリと、そのリポジトリが要求できる対象owner/リポジトリ/権限の一覧です。",
            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
        )

        val currentPolicies = policies
        when {
            loadError != null -> SpanText(loadError!!, modifier = Modifier.color(Colors.Red).fontSize(FontSize.Small))
            currentPolicies == null -> SpanText("読み込み中...", modifier = Modifier.fontSize(FontSize.Small))
            currentPolicies.isEmpty() -> SpanText("許可設定がありません", modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
            else -> currentPolicies.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(topBottom = 4.px),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        SpanText(entry.callerRepository, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                        SpanText(
                            "→ owner=${entry.allowedOwner} / repos=${entry.allowedRepositories.joinToString(", ")} / " +
                                "permissions=${entry.allowedPermissions.entries.joinToString(", ") { (k, v) -> "$k:$v" }}",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.px)) {
                        Button(onClick = {
                            editingCallerRepository = entry.callerRepository
                            callerRepositoryInput = entry.callerRepository
                            allowedOwnerInput = entry.allowedOwner
                            allowedRepositoriesInput = entry.allowedRepositories.joinToString(", ")
                            allowedPermissionsInput = entry.allowedPermissions.entries.joinToString(", ") { (k, v) -> "$k:$v" }
                            formError = null
                        }) { SpanText("編集") }
                        Button(onClick = {
                            scope.launch {
                                try {
                                    deleteCiTokenPolicy(httpClient, accessToken, entry.callerRepository)
                                    if (editingCallerRepository == entry.callerRepository) resetForm()
                                    onChanged()
                                } catch (e: Exception) {
                                    loadError = "削除に失敗しました"
                                }
                            }
                        }) { SpanText("削除", modifier = Modifier.color(Color("#E34948"))) }
                    }
                }
            }
        }

        SpanText(
            if (editingCallerRepository != null) "設定を編集" else "新しい呼び出し元を追加",
            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small).padding(top = 8.px)
        )
        Input(type = InputType.Text) {
            value(callerRepositoryInput)
            placeholder("呼び出し元リポジトリ(例: OneServerMC/RpgCore)")
            if (editingCallerRepository != null) attr("readonly", "readonly")
            onInput { event -> callerRepositoryInput = event.value }
        }
        Input(type = InputType.Text) {
            value(allowedOwnerInput)
            placeholder("対象owner(例: OneServerMC)")
            onInput { event -> allowedOwnerInput = event.value }
        }
        Input(type = InputType.Text) {
            value(allowedRepositoriesInput)
            placeholder("対象リポジトリ名をカンマ区切りで(例: infra, another-repo)")
            onInput { event -> allowedRepositoriesInput = event.value }
        }
        Input(type = InputType.Text) {
            value(allowedPermissionsInput)
            placeholder("許可する権限をカンマ区切りで(例: contents:write)")
            onInput { event -> allowedPermissionsInput = event.value }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.px),
            horizontalArrangement = Arrangement.spacedBy(8.px),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val callerRepository = callerRepositoryInput.trim()
                    val allowedOwner = allowedOwnerInput.trim()
                    val allowedRepositories = allowedRepositoriesInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val allowedPermissions = allowedPermissionsInput.split(",")
                        .mapNotNull { part ->
                            val trimmed = part.trim()
                            if (trimmed.isEmpty()) return@mapNotNull null
                            val (key, value) = trimmed.split(":", limit = 2).let {
                                if (it.size == 2) it[0].trim() to it[1].trim() else return@mapNotNull null
                            }
                            key to value
                        }
                        .toMap()

                    formError = when {
                        callerRepository.isEmpty() -> "呼び出し元リポジトリを入力してください"
                        allowedOwner.isEmpty() -> "対象ownerを入力してください"
                        allowedRepositories.isEmpty() -> "対象リポジトリを1つ以上入力してください"
                        allowedPermissions.isEmpty() -> "許可する権限を「key:value」形式で1つ以上入力してください"
                        else -> null
                    }
                    if (formError != null || saving) return@Button

                    saving = true
                    scope.launch {
                        try {
                            upsertCiTokenPolicy(
                                httpClient,
                                accessToken,
                                CiTokenPolicyEntry(
                                    callerRepository = callerRepository,
                                    allowedOwner = allowedOwner,
                                    allowedRepositories = allowedRepositories,
                                    allowedPermissions = allowedPermissions
                                )
                            )
                            resetForm()
                            onChanged()
                        } catch (e: Exception) {
                            formError = "保存に失敗しました"
                        }
                        saving = false
                    }
                },
                enabled = !saving
            ) {
                SpanText(if (saving) "保存中..." else if (editingCallerRepository != null) "更新" else "追加")
            }
            if (editingCallerRepository != null) {
                Button(onClick = { resetForm() }) { SpanText("キャンセル") }
            }
            formError?.let { SpanText(it, modifier = Modifier.color(Colors.Red).fontSize(FontSize.Small)) }
        }
    }
}

@Composable
private fun InstallationCard(installation: GithubInstallation, httpClient: HttpClient, accessToken: String) {
    var repositoriesInput by remember { mutableStateOf("") }
    var issuedToken by remember { mutableStateOf<GithubInstallationTokenResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var issuing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        SpanText(
            installation.account?.login ?: "installation #${installation.id}",
            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium)
        )
        SpanText(
            "対象リポジトリ: ${installation.repositorySelection ?: "unknown"}",
            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
        )
        SpanText(
            "権限: " + installation.permissions.entries.joinToString(", ") { (key, value) -> "$key:$value" },
            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
        )

        SpanText(
            "リポジトリ名をカンマ区切りで指定するとそのリポジトリのみに限定したトークンを発行できます(空欄ならインストール全体)",
            modifier = Modifier.fontSize(FontSize.Small)
        )
        Input(type = InputType.Text) {
            value(repositoriesInput)
            placeholder("kigawa-net-k8s, admin-panel")
            onInput { event -> repositoriesInput = event.value }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.px),
            horizontalArrangement = Arrangement.spacedBy(8.px),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (!issuing) {
                        issuing = true
                        errorMessage = null
                        val repositories = repositoriesInput
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .ifEmpty { null }
                        scope.launch {
                            issuedToken = try {
                                issueGithubInstallationToken(
                                    httpClient,
                                    accessToken,
                                    installation.id,
                                    repositories = repositories,
                                    permissions = null
                                )
                            } catch (e: Exception) {
                                errorMessage = "トークンの発行に失敗しました"
                                null
                            }
                            issuing = false
                        }
                    }
                },
                enabled = !issuing
            ) {
                SpanText(if (issuing) "発行中..." else "トークン発行")
            }
            errorMessage?.let { SpanText(it, modifier = Modifier.color(Colors.Red)) }
        }

        issuedToken?.let { result ->
            Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
                SpanText(
                    "この画面を離れると再表示できません。今すぐ保存してください。",
                    modifier = Modifier.color(Color("#E34948")).fontWeight(FontWeight.Bold).fontSize(FontSize.Small)
                )
                TextArea(value = result.token) {
                    attr("readonly", "readonly")
                }
                SpanText(
                    "有効期限: ${result.expiresAt}",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            }
        }
    }
}
