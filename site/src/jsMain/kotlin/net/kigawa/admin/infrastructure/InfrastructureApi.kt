package net.kigawa.admin.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object InfrastructureApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

/**
 * サーバーが4xx/5xxを返したときに投げられる例外(issue #184、組織管理の
 * OrganizationApiExceptionと同じ扱い)。メッセージにはサーバーが返した理由が
 * 入るため、そのまま画面に表示できる。
 * statusCode も保持し、呼び出し側で 401/403/5xx を判別可能にする。
 */
class InfrastructureApiException(message: String, val statusCode: Int) : Exception(message)

/** サーバー側のエラー応答本文(`mapOf("error" to ...)`)に対応する。 */
@Serializable
private data class ApiErrorDto(val error: String? = null)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * 2xx以外なら、ステータスに応じたメッセージを例外として投げる。
 *
 * Ktorの`expectSuccess`は無効のため非2xxは例外にならず、そのまま`.body()`を呼ぶと
 * 401の `{"error":"invalid or missing token"}` を型付きモデルへデコードしてしまい、
 * 最終的に「インフラ構成を取得できませんでした」だけに潰れていた(issue #184)。
 * ステータスと本文を明示的に扱い、401/403/5xxを区別して画面に出せるようにする。
 */
private suspend fun HttpResponse.throwIfNotSuccess(): HttpResponse {
    if (status.value in 200..299) return this
    val serverMessage = runCatching {
        errorJson.decodeFromString<ApiErrorDto>(bodyAsText()).error
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val message = when (status.value) {
        // 401は期限切れトークンの典型。英語のサーバーメッセージの代わりに再試行の案内を出す。
        401 -> "セッションの有効期限が切れています。再ログインまたは再試行してください。"
        // 403は認可拒否(RBAC #183導入後)。権限不足であることが分かる文言にする。
        403 -> "インフラ構成を表示する権限がありません。"
        else -> serverMessage ?: "リクエストに失敗しました (HTTP ${status.value})"
    }
    throw InfrastructureApiException(message, status.value)
}

suspend fun fetchInfrastructureTopology(client: HttpClient, accessToken: String): InfrastructureTopology {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** ホスト×カテゴリ単位の細粒度取得(issue #158)。呼び出し側で並列に叩き、届いた部分から順次描画する。 */
suspend fun fetchHostVms(client: HttpClient, accessToken: String, hostName: String): List<InfraVm> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/vms") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun fetchHostDisks(client: HttpClient, accessToken: String, hostName: String): List<InfraDisk> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/disks") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun fetchHostPciDevices(client: HttpClient, accessToken: String, hostName: String): List<InfraPciDevice> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/pci") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

suspend fun fetchHostHwStatus(client: HttpClient, accessToken: String, hostName: String): InfraHostHwStatus {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/hw-status") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** ホストの空きスロット調査結果を取得する(admin-panel#156)。 */
suspend fun fetchHostSlots(client: HttpClient, accessToken: String, hostName: String): HostSlotInventory {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/slots") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** k8sノードの空きスロット調査結果を取得する。VM系ノードはvirtualized=trueで返る。 */
suspend fun fetchNodeSlots(client: HttpClient, accessToken: String, nodeName: String): HostSlotInventory {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/nodes/$nodeName/slots") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** ホストのマウントポイント別ディスク使用率を取得する(admin-panel#148)。
 * SSH未設定時は503で失敗するため、呼び出し側は空のまま扱う。 */
suspend fun fetchHostDiskUsage(client: HttpClient, accessToken: String, hostName: String): List<DiskUsage> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/disk-usage") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** k8sノードのマウントポイント別ディスク使用率を取得する(admin-panel#148)。 */
suspend fun fetchNodeDiskUsage(client: HttpClient, accessToken: String, nodeName: String): List<DiskUsage> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/nodes/$nodeName/disk-usage") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** リソース使用量グラフ用の時系列を取得する(issue #132)。 */
suspend fun fetchResourceUsage(client: HttpClient, accessToken: String, rangeMinutes: Int): ResourceUsageResponse {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/resource-usage?rangeMinutes=$rangeMinutes") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}

/** グルーピングされたリソース使用量を取得する(issue #147)。 */
suspend fun fetchGroupedResourceUsage(client: HttpClient, accessToken: String, rangeMinutes: Int): GroupedResourceUsageResponse {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/resource-usage-grouped?rangeMinutes=$rangeMinutes") {
        bearerAuth(accessToken)
    }.throwIfNotSuccess().body()
}
