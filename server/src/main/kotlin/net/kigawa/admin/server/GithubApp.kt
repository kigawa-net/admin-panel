package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Operates the "kigawa-net" GitHub App (app_id 4316503, contents:write, installed org-wide) so
 * admins can mint scoped, short-lived installation tokens from the admin-panel UI instead of a
 * long-lived PAT. See kigawa-net/admin-panel#41 and kigawa-net/kinfra#348 (the same App backs a
 * CI composite action there).
 */
private val githubAppId = System.getenv("GITHUB_APP_ID") ?: "4316503"
private val githubAppPrivateKeyPem = System.getenv("GITHUB_APP_PRIVATE_KEY")

@Serializable
data class GithubInstallationAccount(
    @SerialName("login") val login: String
)

@Serializable
data class GithubInstallation(
    @SerialName("id") val id: Long,
    @SerialName("account") val account: GithubInstallationAccount? = null,
    @SerialName("repository_selection") val repositorySelection: String? = null,
    @SerialName("permissions") val permissions: Map<String, String> = emptyMap()
)

@Serializable
data class GithubInstallationTokenRequest(
    @SerialName("repositories") val repositories: List<String>? = null,
    @SerialName("permissions") val permissions: Map<String, String>? = null
)

@Serializable
data class GithubRepository(
    @SerialName("full_name") val fullName: String
)

@Serializable
data class GithubInstallationTokenResponse(
    @SerialName("token") val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("permissions") val permissions: Map<String, String> = emptyMap(),
    @SerialName("repositories") val repositories: List<GithubRepository>? = null
)

@Serializable
data class GithubErrorResponse(@SerialName("message") val message: String? = null)

object GithubApp {
    val isConfigured: Boolean get() = !githubAppPrivateKeyPem.isNullOrBlank()

    suspend fun listInstallations(client: HttpClient): List<GithubInstallation> {
        val jwt = buildAppJwt(githubAppId, requireNotNull(githubAppPrivateKeyPem))
        return client.get("https://api.github.com/app/installations") {
            applyAppAuthHeaders(jwt)
        }.body()
    }

    suspend fun createInstallationToken(
        client: HttpClient,
        installationId: Long,
        repositories: List<String>?,
        permissions: Map<String, String>?
    ): GithubInstallationTokenResponse {
        val jwt = buildAppJwt(githubAppId, requireNotNull(githubAppPrivateKeyPem))
        return client.post("https://api.github.com/app/installations/$installationId/access_tokens") {
            applyAppAuthHeaders(jwt)
            contentType(ContentType.Application.Json)
            setBody(GithubInstallationTokenRequest(repositories = repositories, permissions = permissions))
        }.body()
    }

    private fun HttpRequestBuilder.applyAppAuthHeaders(jwt: String) {
        header("Authorization", "Bearer $jwt")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /** Builds a short-lived (10 min) JWT identifying the App itself, per GitHub App authentication. */
    internal fun buildAppJwt(appId: String, privateKeyPem: String): String {
        val now = Instant.now().epochSecond
        val header = """{"alg":"RS256","typ":"JWT"}"""
        // iat set 60s in the past to tolerate clock drift, per GitHub's recommendation.
        val payload = """{"iat":${now - 60},"exp":${now + 540},"iss":"$appId"}"""
        val signingInput = "${base64UrlEncode(header.toByteArray())}.${base64UrlEncode(payload.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(loadPrivateKey(privateKeyPem))
            update(signingInput.toByteArray())
        }.sign()
        return "$signingInput.${base64UrlEncode(signature)}"
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodePemBody(pem: String): ByteArray {
        val base64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(base64)
    }

    private fun loadPrivateKey(pem: String): PrivateKey {
        val der = decodePemBody(pem)
        val pkcs8Der = if (pem.contains("BEGIN RSA PRIVATE KEY")) wrapPkcs1InPkcs8(der) else der
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
    }

    /**
     * Wraps a PKCS#1 RSAPrivateKey DER blob (the format GitHub hands out for App private keys) in
     * a minimal PKCS#8 PrivateKeyInfo structure, since java.security only loads PKCS#8 directly and
     * pulling in a crypto library just for this one conversion isn't worth the dependency.
     */
    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        val rsaOid = byteArrayOf(
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01
        )
        val algorithmIdentifier = byteArrayOf(0x30, (rsaOid.size + 2).toByte()) + rsaOid + byteArrayOf(0x05, 0x00)
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val octetString = byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
        val body = version + algorithmIdentifier + octetString
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }
}
