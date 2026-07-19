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
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    LaunchedEffect(accessToken) {
        state = try {
            GithubAppUiState.Loaded(fetchGithubInstallations(httpClient, accessToken))
        } catch (e: Exception) {
            GithubAppUiState.Error("GitHub Appの情報を取得できませんでした")
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
                is GithubAppUiState.Error -> SpanText(current.message, modifier = Modifier.color(Colors.Red))
                is GithubAppUiState.Loaded -> if (current.installations.isEmpty()) {
                    SpanText("インストール済みのGitHub Appがありません")
                } else {
                    current.installations.forEach { installation ->
                        InstallationCard(installation = installation, httpClient = httpClient, accessToken = accessToken)
                    }
                }
            }
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
