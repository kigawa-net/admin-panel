package net.kigawa.admin.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubActionsOidcTest {
    // GithubActionsOidc caches fetched JWKS by kid in a process-wide singleton, so each test
    // instance needs its own unique kid to avoid one test's cached key leaking into another's.
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val kid = "test-kid-${java.util.UUID.randomUUID()}"
    private val audience = "https://admin.kigawa.net"

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jwks(): String {
        val publicKey = keyPair.public as RSAPublicKey
        val n = base64Url(publicKey.modulus.toByteArray())
        val e = base64Url(publicKey.publicExponent.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"$kid","n":"$n","e":"$e"}]}"""
    }

    private fun token(
        issuer: String = "https://token.actions.githubusercontent.com",
        aud: String? = audience,
        repository: String? = "OneServerMC/RpgCore",
        expiresAt: Long = Instant.now().epochSecond + 300,
        notBefore: Long? = null,
        headerKid: String? = kid,
        signWith: PrivateKey = keyPair.private,
        extraClaims: String = ""
    ): String {
        val header = """{"alg":"RS256"${if (headerKid != null) ",\"kid\":\"$headerKid\"" else ""}}"""
        val payload = buildString {
            append("{\"iss\":\"$issuer\"")
            if (aud != null) append(",\"aud\":\"$aud\"")
            if (repository != null) append(",\"repository\":\"$repository\"")
            append(",\"exp\":$expiresAt")
            if (notBefore != null) append(",\"nbf\":$notBefore")
            if (extraClaims.isNotEmpty()) append(",$extraClaims")
            append("}")
        }
        val signingInput = "${base64Url(header.toByteArray())}.${base64Url(payload.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(signWith)
            update(signingInput.toByteArray())
        }.sign()
        return "$signingInput.${base64Url(signature)}"
    }

    private fun mockClient(jwksBody: String = jwks()): HttpClient {
        val engine = MockEngine {
            respond(
                content = jwksBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `verifies a validly signed token`() = runBlocking {
        val claims = GithubActionsOidc.verify(mockClient(), token(), audience)
        assertEquals("OneServerMC/RpgCore", claims?.repository)
    }

    @Test
    fun `rejects a token signed by an unrelated key`() = runBlocking {
        val otherKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val claims = GithubActionsOidc.verify(mockClient(), token(signWith = otherKeyPair.private), audience)
        assertNull(claims)
    }

    @Test
    fun `rejects a wrong issuer`() = runBlocking {
        val claims = GithubActionsOidc.verify(mockClient(), token(issuer = "https://evil.example.com"), audience)
        assertNull(claims)
    }

    @Test
    fun `rejects a wrong audience`() = runBlocking {
        val claims =
            GithubActionsOidc.verify(mockClient(), token(aud = "https://someone-else.example.com"), audience)
        assertNull(claims)
    }

    @Test
    fun `rejects an expired token`() = runBlocking {
        val claims =
            GithubActionsOidc.verify(mockClient(), token(expiresAt = Instant.now().epochSecond - 60), audience)
        assertNull(claims)
    }

    @Test
    fun `rejects a not-yet-valid token`() = runBlocking {
        val claims = GithubActionsOidc.verify(
            mockClient(),
            token(notBefore = Instant.now().epochSecond + 300),
            audience
        )
        assertNull(claims)
    }

    @Test
    fun `rejects a malformed token`() = runBlocking {
        val claims = GithubActionsOidc.verify(mockClient(), "not-a-jwt", audience)
        assertNull(claims)
    }

    @Test
    fun `rejects an unknown key id`() = runBlocking {
        val claims = GithubActionsOidc.verify(mockClient(), token(headerKid = "unknown-kid"), audience)
        assertNull(claims)
    }

    // ---- issue #227 (PR A): 観測用クレーム。判定は変わらないことを確かめる ----

    @Test
    fun `extracts observed claims from a validly signed token`() = runBlocking {
        val claims = GithubActionsOidc.verify(
            mockClient(),
            token(
                extraClaims = """
                    "repository_id":"1347995166","ref":"refs/heads/main","event_name":"push",
                    "workflow_ref":"kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/main",
                    "job_workflow_ref":"kigawa-net/kinfra/.github/workflows/x.yml@refs/heads/main",
                    "environment":"dev","sha":"abc123","actor":"kigawa"
                """.trimIndent().replace("\n", "")
            ),
            audience
        )
        val observed = claims?.observed
        assertEquals("1347995166", observed?.repositoryId)
        assertEquals("refs/heads/main", observed?.ref)
        assertEquals("push", observed?.eventName)
        assertEquals("kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/main", observed?.workflowRef)
        assertEquals("kigawa-net/kinfra/.github/workflows/x.yml@refs/heads/main", observed?.jobWorkflowRef)
        assertEquals("dev", observed?.environment)
        assertEquals("abc123", observed?.sha)
        assertEquals("kigawa", observed?.actor)
    }

    @Test
    fun `malformed observed claims do not reject a validly signed token`() = runBlocking {
        // 判定に使う repository は従来どおり通る。観測用の値は型違い・長すぎ・制御文字で null になるだけ。
        val tooLong = "a".repeat(OBSERVED_CLAIM_MAX_LENGTH + 1)
        val claims = GithubActionsOidc.verify(
            mockClient(),
            token(
                extraClaims = """
                    "repository_id":1347995166,"event_name":null,
                    "workflow_ref":"$tooLong","job_workflow_ref":"line1\nline2","environment":true
                """.trimIndent().replace("\n", "")
            ),
            audience
        )
        assertEquals("OneServerMC/RpgCore", claims?.repository)
        assertNull(claims?.observed?.repositoryId)
        assertNull(claims?.observed?.ref)
        assertNull(claims?.observed?.eventName)
        assertNull(claims?.observed?.workflowRef)
        assertNull(claims?.observed?.jobWorkflowRef)
        assertNull(claims?.observed?.environment)
    }

    @Test
    fun `a non-string ref still rejects the token as before`() = runBlocking {
        // `ref` は #227 以前から ActionsClaims に含まれ、型違いはデコード失敗 = 401 だった。その挙動は変えない。
        val claims = GithubActionsOidc.verify(
            mockClient(),
            token(extraClaims = "\"ref\":[\"refs/heads/main\"]"),
            audience
        )
        assertNull(claims)
    }

    @Test
    fun `a token whose observed claims miss the expected constraints is still verified`() = runBlocking {
        // 観測の制約(CI_TOKEN_OBSERVE_CONSTRAINTS)は verify() に渡らない。不一致でも検証結果は変わらない。
        val claims = GithubActionsOidc.verify(
            mockClient(),
            token(extraClaims = "\"event_name\":\"pull_request\",\"ref\":\"refs/heads/feature\""),
            audience
        )
        assertEquals("OneServerMC/RpgCore", claims?.repository)
        assertEquals("pull_request", claims?.observed?.eventName)
        assertEquals("refs/heads/feature", claims?.observed?.ref)
    }

    @Test
    fun `observation logs never contain the token, its signature, or unobserved claims`() = runBlocking {
        val marker = "MARKER_UNOBSERVED_CLAIM_${java.util.UUID.randomUUID()}"
        val rawToken = token(extraClaims = "\"secret_note\":\"$marker\"")
        val signature = rawToken.substringAfterLast('.')
        val captured = captureCiTokenLogs {
            val claims = runBlocking { GithubActionsOidc.verify(mockClient(), rawToken, audience) }
            observeCiTokenDecision(
                allowed = true,
                reason = "minted",
                claims = claims,
                owner = "OneServerMC",
                repositories = listOf("infra"),
                permissions = mapOf("contents" to "write")
            )
        }
        assertTrue(captured.isNotEmpty())
        val all = captured.joinToString("\n")
        assertFalse(all.contains(rawToken))
        assertFalse(all.contains(signature))
        assertFalse(all.contains(marker))
        assertFalse(all.contains("Bearer"))
    }
}
