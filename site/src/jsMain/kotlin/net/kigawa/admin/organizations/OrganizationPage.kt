package net.kigawa.admin.organizations

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

private sealed class OrganizationListUiState {
    object Loading : OrganizationListUiState()
    data class Loaded(val organizations: List<Organization>) : OrganizationListUiState()
    data class Error(val message: String) : OrganizationListUiState()
}

@Composable
fun OrganizationPage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<OrganizationListUiState>(OrganizationListUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    var selectedOrg by remember { mutableStateOf<Organization?>(null) }
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
        OrganizationMembersPage(
            accessToken = accessToken,
            organization = currentSelectedOrg,
            onBack = { selectedOrg = null }
        )
        return
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
                    "組織管理",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
            Button(onClick = { showCreateForm = !showCreateForm }) {
                SpanText(if (showCreateForm) "作成フォームを閉じる" else "組織を作成")
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
                CreateOrganizationForm(
                    onCreate = { request ->
                        showCreateForm = false
                        runAction { createOrganization(httpClient, accessToken, request) }
                    }
                )
            }

            when (val current = state) {
                is OrganizationListUiState.Loading -> SpanText("読み込み中...")
                is OrganizationListUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is OrganizationListUiState.Loaded -> current.organizations.forEach { org ->
                    OrganizationCard(
                        organization = org,
                        onManageMembers = { selectedOrg = org },
                        onDelete = {
                            if (window.confirm("${org.name} を削除しますか?元に戻せません。")) {
                                runAction { deleteOrganization(httpClient, accessToken, org.id) }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateOrganizationForm(onCreate: (CreateOrganizationRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(8.px)
    ) {
        SpanText("新規組織作成", modifier = Modifier.fontWeight(FontWeight.Bold))
        TextInput(text = name, onTextChange = { name = it }, placeholder = "組織名")
        TextInput(text = domain, onTextChange = { domain = it }, placeholder = "ドメイン(例: example.com)")
        TextInput(text = description, onTextChange = { description = it }, placeholder = "説明(任意)")
        Button(
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
            SpanText("作成する")
        }
    }
}

@Composable
private fun OrganizationCard(
    organization: Organization,
    onManageMembers: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(4.px)
    ) {
        SpanText(organization.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
        organization.description?.takeIf { it.isNotBlank() }?.let {
            SpanText(it, modifier = Modifier.color(Colors.Gray))
        }
        val domainNames = organization.domains.joinToString(", ") { it.name }
        if (domainNames.isNotBlank()) {
            SpanText(domainNames, modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.px),
            horizontalArrangement = Arrangement.spacedBy(8.px)
        ) {
            Button(onClick = { onManageMembers() }) { SpanText("メンバー管理") }
            Button(onClick = { onDelete() }) { SpanText("削除") }
        }
    }
}

private sealed class OrganizationMembersUiState {
    object Loading : OrganizationMembersUiState()
    data class Loaded(val members: List<OrganizationMember>) : OrganizationMembersUiState()
    data class Error(val message: String) : OrganizationMembersUiState()
}

@Composable
private fun OrganizationMembersPage(accessToken: String, organization: Organization, onBack: () -> Unit) {
    var state by remember { mutableStateOf<OrganizationMembersUiState>(OrganizationMembersUiState.Loading) }
    var refreshKey by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
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
                    "${organization.name} のメンバー",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
            Button(onClick = { showAddForm = !showAddForm }) {
                SpanText(if (showAddForm) "追加フォームを閉じる" else "メンバーを追加")
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            statusMessage?.let { message ->
                SpanText(message, modifier = Modifier.color(Colors.Blue))
            }

            if (showAddForm) {
                AddMemberForm(
                    httpClient = httpClient,
                    accessToken = accessToken,
                    onAdd = { userId ->
                        showAddForm = false
                        runAction { addOrganizationMember(httpClient, accessToken, organization.id, userId) }
                    }
                )
            }

            when (val current = state) {
                is OrganizationMembersUiState.Loading -> SpanText("読み込み中...")
                is OrganizationMembersUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is OrganizationMembersUiState.Loaded -> current.members.forEach { member ->
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
                            SpanText(member.username, modifier = Modifier.fontWeight(FontWeight.Bold))
                            if (!member.email.isNullOrBlank()) {
                                SpanText(member.email, modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small))
                            }
                        }
                        Button(onClick = {
                            if (window.confirm("${member.username} を ${organization.name} から削除しますか?")) {
                                runAction { removeOrganizationMember(httpClient, accessToken, organization.id, member.id) }
                            }
                        }) {
                            SpanText("削除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMemberForm(
    httpClient: HttpClient,
    accessToken: String,
    onAdd: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<OrganizationMember>>(emptyList()) }
    var selectedUserId by remember { mutableStateOf<String?>(null) }
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
        SpanText("メンバーを追加", modifier = Modifier.fontWeight(FontWeight.Bold))
        TextInput(text = query, onTextChange = { query = it }, placeholder = "ユーザー名またはメールアドレスで検索")
        Button(
            onClick = {
                val currentQuery = query
                if (currentQuery.isNotBlank()) {
                    scope.launch {
                        searchResults = try {
                            searchKigawaNetUsers(httpClient, accessToken, currentQuery).users
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
            },
            enabled = query.isNotBlank()
        ) {
            SpanText("検索")
        }
        searchResults.forEach { user ->
            val isSelected = selectedUserId == user.id
            Row(
                modifier = Modifier.fillMaxWidth().padding(topBottom = 4.px),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpanText(
                    "${user.username}${user.email?.let { " ($it)" } ?: ""}",
                    modifier = if (isSelected) Modifier.color(Color("#2A78D6")) else Modifier
                )
                Button(onClick = { selectedUserId = user.id }) {
                    SpanText(if (isSelected) "選択中" else "選択")
                }
            }
        }
        Button(
            onClick = { selectedUserId?.let { onAdd(it) } },
            enabled = selectedUserId != null
        ) {
            SpanText("追加する")
        }
    }
}
