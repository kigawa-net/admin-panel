package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

private const val GITHUB_ACTIONS_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
private const val JWKS_URL = "$GITHUB_ACTIONS_OIDC_ISSUER/.well-known/jwks"
private const val JWKS_CACHE_TTL_MILLIS = 10 * 60 * 1000L

private val jwtJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class JwtHeader(
    val alg: String,
    val kid: String? = null
)

@Serializable
data class ActionsClaims(
    @SerialName("iss") val issuer: String,
    @SerialName("aud") val audience: String? = null,
    @SerialName("repository") val repository: String? = null,
    @SerialName("ref") val ref: String? = null,
    @SerialName("exp") val expiresAt: Long,
    @SerialName("nbf") val notBefore: Long? = null
)

@Serializable
private data class Jwk(
    val kty: String,
    val kid: String,
    val n: String,
    val e: String
)

@Serializable
private data class JwksResponse(val keys: List<Jwk> = emptyList())

/**
 * Verifies GitHub Actions OIDC tokens (the JWTs a workflow can mint for itself via
 * `permissions: id-token: write`) against GitHub's own published signing keys, so a caller's
 * identity (its `repository` claim) can be trusted without either side holding a shared secret.
 */
object GithubActionsOidc {
    private data class CacheEntry(val keysByKid: Map<String, Jwk>, val fetchedAtMillis: Long)

    private val cache = AtomicReference<CacheEntry?>(null)

    /**
     * Returns the verified claims on success, or null on ANY failure (malformed token, unknown
     * key id, bad signature, wrong issuer/audience, or expired/not-yet-valid) — callers must treat
     * null uniformly as "unauthenticated" rather than branching on the failure reason.
     */
    suspend fun verify(client: HttpClient, bearerToken: String, expectedAudience: String): ActionsClaims? {
        val parts = bearerToken.split(".")
        if (parts.size != 3) return null
        val (headerPart, payloadPart, signaturePart) = parts

        val header = runCatching {
            jwtJson.decodeFromString<JwtHeader>(String(base64UrlDecode(headerPart)))
        }.getOrNull() ?: return null
        if (header.alg != "RS256") return null

        val claims = runCatching {
            jwtJson.decodeFromString<ActionsClaims>(String(base64UrlDecode(payloadPart)))
        }.getOrNull() ?: return null

        if (claims.issuer != GITHUB_ACTIONS_OIDC_ISSUER) return null
        if (claims.audience != expectedAudience) return null
        val now = Instant.now().epochSecond
        if (claims.expiresAt <= now) return null
        if (claims.notBefore != null && claims.notBefore > now) return null

        val jwk = findKey(client, header.kid) ?: return null
        val publicKey = runCatching { buildRsaPublicKey(jwk) }.getOrNull() ?: return null

        val signingInput = "$headerPart.$payloadPart".toByteArray()
        val signatureValid = runCatching {
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(signingInput)
            }.verify(base64UrlDecode(signaturePart))
        }.getOrDefault(false)

        return if (signatureValid) claims else null
    }

    private suspend fun findKey(client: HttpClient, kid: String?): Jwk? {
        if (kid.isNullOrBlank()) return null
        val cached = cache.get()
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAtMillis < JWKS_CACHE_TTL_MILLIS) {
            cached.keysByKid[kid]?.let { return it }
        }
        // Cache missing, expired, or simply doesn't have this kid yet (e.g. GitHub rotated keys
        // since our last fetch) — refetch once rather than trusting a stale "no such kid".
        val fetched = client.get(JWKS_URL).body<JwksResponse>().keys.associateBy { it.kid }
        cache.set(CacheEntry(fetched, now))
        return fetched[kid]
    }

    private fun buildRsaPublicKey(jwk: Jwk): PublicKey {
        val modulus = BigInteger(1, base64UrlDecode(jwk.n))
        val exponent = BigInteger(1, base64UrlDecode(jwk.e))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private fun base64UrlDecode(value: String): ByteArray {
        val padding = (4 - value.length % 4) % 4
        return Base64.getUrlDecoder().decode(value + "=".repeat(padding))
    }
}
