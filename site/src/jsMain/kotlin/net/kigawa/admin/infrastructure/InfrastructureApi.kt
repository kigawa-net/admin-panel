package net.kigawa.admin.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get

object InfrastructureApiConfig {
    const val baseUrl = "https://admin.kigawa.net/api"
}

suspend fun fetchInfrastructureTopology(client: HttpClient, accessToken: String): InfrastructureTopology {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure") {
        bearerAuth(accessToken)
    }.body()
}

/** ホスト×カテゴリ単位の細粒度取得(issue #158)。呼び出し側で並列に叩き、届いた部分から順次描画する。 */
suspend fun fetchHostVms(client: HttpClient, accessToken: String, hostName: String): List<InfraVm> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/vms") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun fetchHostDisks(client: HttpClient, accessToken: String, hostName: String): List<InfraDisk> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/disks") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun fetchHostPciDevices(client: HttpClient, accessToken: String, hostName: String): List<InfraPciDevice> {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/pci") {
        bearerAuth(accessToken)
    }.body()
}

suspend fun fetchHostHwStatus(client: HttpClient, accessToken: String, hostName: String): InfraHostHwStatus {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/hw-status") {
        bearerAuth(accessToken)
    }.body()
}

/** ホストの空きスロット調査結果を取得する(admin-panel#156)。 */
suspend fun fetchHostSlots(client: HttpClient, accessToken: String, hostName: String): HostSlotInventory {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/hosts/$hostName/slots") {
        bearerAuth(accessToken)
    }.body()
}

/** リソース使用量グラフ用の時系列を取得する(issue #132)。 */
suspend fun fetchResourceUsage(client: HttpClient, accessToken: String, rangeMinutes: Int): ResourceUsageResponse {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/resource-usage?rangeMinutes=$rangeMinutes") {
        bearerAuth(accessToken)
    }.body()
}

/** グルーピングされたリソース使用量を取得する(issue #147)。 */
suspend fun fetchGroupedResourceUsage(client: HttpClient, accessToken: String, rangeMinutes: Int): GroupedResourceUsageResponse {
    return client.get("${InfrastructureApiConfig.baseUrl}/infrastructure/resource-usage-grouped?rangeMinutes=$rangeMinutes") {
        bearerAuth(accessToken)
    }.body()
}
