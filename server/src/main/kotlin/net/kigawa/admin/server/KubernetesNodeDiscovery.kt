package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val SERVICE_ACCOUNT_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"

@Serializable
internal data class K8sNodeList(val items: List<K8sNode> = emptyList())

@Serializable
internal data class K8sNode(
    val metadata: K8sNodeMetadata = K8sNodeMetadata(),
    val status: K8sNodeStatus = K8sNodeStatus()
)

@Serializable
internal data class K8sNodeMetadata(val name: String = "", val labels: Map<String, String> = emptyMap())

@Serializable
internal data class K8sNodeStatus(
    val addresses: List<K8sNodeAddress> = emptyList(),
    val conditions: List<K8sNodeCondition> = emptyList(),
    val capacity: Map<String, String> = emptyMap(),
    val nodeInfo: K8sNodeInfo = K8sNodeInfo()
)

@Serializable
internal data class K8sNodeAddress(val type: String = "", val address: String = "")

@Serializable
internal data class K8sNodeCondition(val type: String = "", val status: String = "")

@Serializable
internal data class K8sNodeInfo(val kubeletVersion: String = "", val osImage: String = "")

internal fun K8sNode.isControlPlane(): Boolean = metadata.labels.keys.any {
    it == "node-role.kubernetes.io/control-plane" || it == "node-role.kubernetes.io/master"
}

/**
 * Secretを一切使わず、Podに自動マウントされるServiceAccountトークン/CA証明書だけを使って
 * クラスタ内から Kubernetes API (`GET /api/v1/nodes`) を直接呼び、実際のノード一覧
 * (名前・内部IP・コントロールプレーンかワーカーか)をその場で取得する。in-cluster 以外
 * (ローカル開発時など)では ServiceAccount ファイルが存在しないため、空リストを返し
 * 呼び出し側が静的フォールバックを使う。
 */
suspend fun discoverKubernetesNodes(): List<NetworkDeviceDto> {
    val apiServerUrl = inClusterApiServerUrl() ?: return emptyList()
    val token = readServiceAccountToken() ?: return emptyList()
    val client = buildKubernetesHttpClient() ?: return emptyList()

    return try {
        val nodeList = client.get("$apiServerUrl/api/v1/nodes") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<K8sNodeList>()

        val controlPlanes = mutableListOf<K8sNode>()
        val workers = mutableListOf<K8sNode>()
        for (node in nodeList.items) {
            (if (node.isControlPlane()) controlPlanes else workers).add(node)
        }

        val devices = mutableListOf<NetworkDeviceDto>()
        controlPlanes.forEachIndexed { index, node ->
            devices.add(toDeviceDto(node, "CONTROL_PLANE", "Kubernetes コントロールプレーン", xForIndex(index, controlPlanes.size), 0.35f))
        }
        workers.forEachIndexed { index, node ->
            devices.add(toDeviceDto(node, "WORKER", "Kubernetes ワーカーノード", xForIndex(index, workers.size), 0.65f))
        }
        devices
    } catch (e: Exception) {
        emptyList()
    } finally {
        client.close()
    }
}

private fun toDeviceDto(node: K8sNode, type: String, purpose: String, x: Float, y: Float): NetworkDeviceDto {
    val ip = node.status.addresses.firstOrNull { it.type == "InternalIP" }?.address ?: "-"
    return NetworkDeviceDto(
        id = node.metadata.name,
        name = node.metadata.name,
        type = type,
        ipAddress = ip,
        purpose = purpose,
        x = x,
        y = y
    )
}

private fun xForIndex(index: Int, count: Int): Float {
    if (count <= 1) return 0.5f
    return 0.1f + (0.8f * index / (count - 1))
}

internal fun inClusterApiServerUrl(): String? {
    val host = System.getenv("KUBERNETES_SERVICE_HOST") ?: return null
    val port = System.getenv("KUBERNETES_SERVICE_PORT") ?: "443"
    return "https://$host:$port"
}

internal fun readServiceAccountToken(): String? {
    val file = File("$SERVICE_ACCOUNT_DIR/token")
    return if (file.exists()) file.readText().trim() else null
}

internal fun buildKubernetesHttpClient(): HttpClient? {
    val caTrustManager = loadClusterCaTrustManager() ?: return null
    return HttpClient(CIO) {
        engine {
            https {
                trustManager = caTrustManager
            }
        }
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

private fun loadClusterCaTrustManager(): X509TrustManager? {
    val caFile = File("$SERVICE_ACCOUNT_DIR/ca.crt")
    if (!caFile.exists()) return null
    return try {
        val certificate = caFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("kube-ca", certificate)
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) {
        null
    }
}
