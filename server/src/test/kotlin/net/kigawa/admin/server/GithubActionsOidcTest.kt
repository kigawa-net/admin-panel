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
import kotlin.test.assertNull

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
        signWith: PrivateKey = keyPair.private
    ): String {
        val header = """{"alg":"RS256"${if (headerKid != null) ",\"kid\":\"$headerKid\"" else ""}}"""
        val payload = buildString {
            append("{\"iss\":\"$issuer\"")
            if (aud != null) append(",\"aud\":\"$aud\"")
            if (repository != null) append(",\"repository\":\"$repository\"")
            append(",\"exp\":$expiresAt")
            if (notBefore != null) append(",\"nbf\":$notBefore")
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
}
