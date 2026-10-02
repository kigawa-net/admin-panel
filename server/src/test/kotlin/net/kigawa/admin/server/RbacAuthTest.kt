package net.kigawa.admin.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * issue #183: 認証・認可の主要ケース。
 * - requireRole の判定(viewer/operator/admin のマトリクス)
 * - AdminRole の包含(admin ⊃ operator ⊃ viewer)
 * - aud / iss / exp 検証の失敗時の挙動(401)
 * - ロール未整備トークンの挙動(厳格時は403、緊急回避時は許可)
 * - JWKSのキャッシュ・鍵ローテーション・取得失敗時の挙動
 */
class RbacAuthTest {

    // ---------------------------------------------------------------- 認可判定

    @Test
    fun adminRoleIncludesLowerRoles() {
        val roles = adminRolesFrom(listOf("admin"))
        assertEquals(setOf(AdminRole.ADMIN, AdminRole.OPERATOR, AdminRole.VIEWER), roles)
    }

    @Test
    fun operatorRoleIncludesViewerButNotAdmin() {
        val roles = adminRolesFrom(listOf("operator"))
        assertEquals(setOf(AdminRole.OPERATOR, AdminRole.VIEWER), roles)
    }

    @Test
    fun viewerRoleOnlyAllowsViewing() {
        val roles = adminRolesFrom(listOf("viewer"))
        assertEquals(setOf(AdminRole.VIEWER), roles)
        assertTrue(roles.contains(AdminRole.VIEWER))
        assertTrue(!roles.contains(AdminRole.OPERATOR))
        assertTrue(!roles.contains(AdminRole.ADMIN))
    }

    @Test
    fun unknownOrEmptyRolesYieldNoPermission() {
        assertEquals(emptySet(), adminRolesFrom(emptyList()))
        assertEquals(emptySet(), adminRolesFrom(listOf("superuser", "master")))
        // セットに1つも無ければ閲覧系も403(issueのマトリクス通り)
        val principal = AdminPrincipal("user-1", adminRolesFrom(emptyList()))
        assertTrue(!principal.hasRole(AdminRole.VIEWER))
    }

    @Test
    fun principalHasRoleMatchesMatrix() {
        val admin = AdminPrincipal("user-1", adminRolesFrom(listOf("admin")))
        assertTrue(admin.hasRole(AdminRole.VIEWER))
        assertTrue(admin.hasRole(AdminRole.OPERATOR))
        assertTrue(admin.hasRole(AdminRole.ADMIN))

        val operator = AdminPrincipal("user-2", adminRolesFrom(listOf("operator")))
        assertTrue(operator.hasRole(AdminRole.VIEWER))
        assertTrue(operator.hasRole(AdminRole.OPERATOR))
        assertTrue(!operator.hasRole(AdminRole.ADMIN))

        val viewer = AdminPrincipal("user-3", adminRolesFrom(listOf("viewer")))
        assertTrue(viewer.hasRole(AdminRole.VIEWER))
        assertTrue(!viewer.hasRole(AdminRole.OPERATOR))
    }

    // ---------------------------------------------------------------- 設定

    @Test
    fun configDefaultsToKigawaNetRealm() {
        val config = RbacConfig.fromEnv { null }
        assertEquals(DEFAULT_KEYCLOAK_JWKS_URL, config.jwksUrl)
        assertEquals("https://user.kigawa.net/realms/kigawa-net", config.issuer)
        assertEquals("admin-panel", config.audience)
        assertEquals(null, config.azp)
        assertTrue(config.rbacEnforced)
    }

    @Test
    fun configParsesEnforcementFlag() {
        val off = RbacConfig.fromEnv { key -> if (key == "KEYCLOAK_RBAC_ENFORCED") "false" else null }
        assertTrue(!off.rbacEnforced)
        val on = RbacConfig.fromEnv { key -> if (key == "KEYCLOAK_RBAC_ENFORCED") "true" else null }
        assertTrue(on.rbacEnforced)
    }

    // ------------------------------------------------------- HTTP認証・認可

    @Test
    fun unauthenticatedRequestReturns401() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, call("/view", token = null))
        assertEquals(HttpStatusCode.Unauthorized, call("/view", token = "not-a-jwt"))
    }

    @Test
    fun viewerCanViewButCannotOperateOrManage() = withServer {
        val token = tokens.token(roles = listOf("viewer"))
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.Forbidden, call("/operate", token))
        assertEquals(HttpStatusCode.Forbidden, call("/manage", token))
    }

    @Test
    fun operatorCanOperateButCannotManage() = withServer {
        val token = tokens.token(roles = listOf("operator"))
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.OK, call("/operate", token))
        assertEquals(HttpStatusCode.Forbidden, call("/manage", token))
    }

    @Test
    fun adminCanDoEverything() = withServer {
        val token = tokens.token(roles = listOf("admin"))
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.OK, call("/operate", token))
        assertEquals(HttpStatusCode.OK, call("/manage", token))
    }

    @Test
    fun rolesAreReadFromAdminPanelResourceAccess() = withServer {
        // issue記載の resource_access["admin-panel"].roles 形式
        val token = tokens.token(adminPanelRoles = listOf("viewer"))
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.Forbidden, call("/operate", token))

        val adminToken = tokens.token(adminPanelRoles = listOf("admin", "operator", "viewer"))
        assertEquals(HttpStatusCode.OK, call("/manage", adminToken))
    }

    @Test
    fun rolesAreReadFromRealmAccessFallback() = withServer {
        // resource_access が無い構成へのフォールバック(realm_access.roles)
        val token = tokens.token(realmAccessRoles = listOf("operator"))
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.OK, call("/operate", token))
        assertEquals(HttpStatusCode.Forbidden, call("/manage", token))
    }

    @Test
    fun wrongAudienceIsRejectedAs401() = withServer {
        val token = tokens.token(aud = listOf("account"))
        assertEquals(HttpStatusCode.Unauthorized, call("/view", token))
    }

    @Test
    fun wrongIssuerIsRejectedAs401() = withServer {
        val token = tokens.token(iss = "https://other.example/realms/other")
        assertEquals(HttpStatusCode.Unauthorized, call("/view", token))
    }

    @Test
    fun expiredTokenIsRejectedAs401() = withServer {
        val token = tokens.token(expSeconds = -60)
        assertEquals(HttpStatusCode.Unauthorized, call("/view", token))
    }

    @Test
    fun rolelessTokenIsForbiddenWhenRbacEnforced() = withServer {
        // ロール未整備の環境(issueマトリクス通り・安全側)
        val token = tokens.token()
        assertEquals(HttpStatusCode.Forbidden, call("/view", token))
        assertEquals(HttpStatusCode.Forbidden, call("/manage", token))
    }

    @Test
    fun rolelessTokenFallsBackToAdminWhenRbacNotEnforced() = withServer(
        config = testConfig().copy(rbacEnforced = false)
    ) {
        // 緊急回避: KEYCLOAK_RBAC_ENFORCED=false のときだけ旧来どおり「ログインのみ」
        val token = tokens.token()
        assertEquals(HttpStatusCode.OK, call("/view", token))
        assertEquals(HttpStatusCode.OK, call("/manage", token))
    }

    @Test
    fun azpMismatchIsRejectedWhenConfigured() = withServer(config = testConfig().copy(azp = "admin-panel")) {
        val otherClient = tokens.token(azp = "another-client")
        assertEquals(HttpStatusCode.Unauthorized, call("/view", otherClient))
        val expected = tokens.token(azp = "admin-panel", roles = listOf("viewer"))
        assertEquals(HttpStatusCode.OK, call("/view", expected))
    }

    // --------------------------------------------------------------- JWKS

    @Test
    fun jwksIsCachedAndRefreshedForUnknownKid() {
        var fetches = 0
        var keys: List<Jwk> = listOf(tokens.jwk(tokens.kid))
        val provider = RefreshingJwkProvider(
            jwksUrl = "https://example.invalid/jwks",
            cacheTtlMillis = 60_000,
            minRefreshIntervalMillis = 0,
            fetchOverride = {
                fetches++
                keys
            }
        )
        assertNotNull(provider.get(tokens.kid))
        assertNotNull(provider.get(tokens.kid))
        assertEquals(1, fetches, "TTL内はキャッシュを使う(2回目は再取得しない)")

        // 鍵ローテーション: 新しいkidの鍵だけになったら未知kid経由で再取得する
        keys = listOf(jwkOf("rotated-key", tokens.publicKey))
        assertNotNull(provider.get("rotated-key"))
        assertEquals(2, fetches)
    }

    @Test
    fun jwksFetchFailureKeepsStaleCache() {
        var fetches = 0
        val provider = RefreshingJwkProvider(
            jwksUrl = "https://example.invalid/jwks",
            cacheTtlMillis = 0, // 毎回の再取得を試みる
            minRefreshIntervalMillis = 0,
            fetchOverride = {
                fetches++
                if (fetches == 1) listOf(tokens.jwk(tokens.kid)) else throw IllegalStateException("network down")
            }
        )
        assertNotNull(provider.get(tokens.kid))
        // 取得失敗でも取得済みキャッシュ(stale)は捨てないので検証は続行できる
        assertNotNull(provider.get(tokens.kid))
        assertEquals(2, fetches)
    }

    @Test
    fun jwksFailureWithoutCacheThrowsKeyNotFound() {
        val provider = RefreshingJwkProvider(
            jwksUrl = "https://example.invalid/jwks",
            minRefreshIntervalMillis = 0,
            fetchOverride = { throw IllegalStateException("network down") }
        )
        // キャッシュが1件も無い状態での取得失敗 → JwkException(= Ktor側で401扱い)
        assertFailsWith<SigningKeyNotFoundException> { provider.get("kid") }
    }

    @Test
    fun warmUpReturnsKeyIds() {
        val provider = RefreshingJwkProvider(
            jwksUrl = "https://example.invalid/jwks",
            fetchOverride = { listOf(tokens.jwk(tokens.kid)) }
        )
        assertEquals(listOf(tokens.kid), provider.warmUp())
    }

    // ---------------------------------------------------------- テスト用装置

    private fun testConfig() = RbacConfig(
        jwksUrl = "https://example.invalid/jwks",
        issuer = ISSUER,
        audience = AUDIENCE,
        azp = null,
        rbacEnforced = true
    )

    /** 認証・認可のHTTPケースを1つのテストアプリケーションで確認する。 */
    private fun withServer(
        config: RbacConfig = testConfig(),
        block: suspend ServerProbe.() -> Unit
    ) = kotlinx.coroutines.runBlocking {
        testApplication {
            application {
                testApi(config)
            }
            val probe = ServerProbe(this)
            probe.block()
        }
        Unit
    }

    private class ServerProbe(private val scope: io.ktor.server.testing.ClientProvider) {
        suspend fun call(path: String, token: String?): HttpStatusCode {
            val response = scope.client.get(path) {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            return response.status
        }
    }

    private fun Application.testApi(config: RbacConfig) {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            keycloakJwt(config, StaticJwkProvider(tokens.jwk(tokens.kid)))
        }
        routing {
            // 実際のアプリと同じ形(JWT認証 + route内のrequireRole)の最小構成
            authenticate("keycloak") {
                routeGet("/view") {
                    val principal = requireRole(AdminRole.VIEWER) ?: return@routeGet
                    call.respondText("view:${principal.userId}")
                }
                routeGet("/operate") {
                    val principal = requireRole(AdminRole.OPERATOR) ?: return@routeGet
                    call.respondText("operate:${principal.userId}")
                }
                routeGet("/manage") {
                    val principal = requireRole(AdminRole.ADMIN) ?: return@routeGet
                    call.respondText("manage:${principal.userId}")
                }
            }
        }
    }

    private class StaticJwkProvider(private val jwk: Jwk) : JwkProvider {
        override fun get(keyId: String?): Jwk = jwk
    }

    companion object {
        private const val ISSUER = "https://example.invalid/realms/test"
        private const val AUDIENCE = "admin-panel"

        private val tokens: TokenFactory by lazy { TokenFactory() }
    }
}

/** RS256でテスト用トークンを組み立てる(JWKS側は [TokenFactory.jwk] で公開鍵を配る)。 */
private class TokenFactory {
    val kid: String = "test-key"
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    fun jwk(keyId: String): Jwk = jwkOf(keyId, publicKey)

    fun token(
        iss: String = RbacAuthTestIssuer,
        sub: String? = "user-1",
        aud: List<String> = listOf("admin-panel"),
        expSeconds: Long = 300,
        azp: String? = null,
        roles: List<String>? = null,
        adminPanelRoles: List<String>? = null,
        realmAccessRoles: List<String>? = null,
        kid: String = this.kid
    ): String {
        val now = System.currentTimeMillis() / 1000
        val payload = buildJsonObject {
            put("iss", iss)
            if (sub != null) put("sub", sub)
            putJsonArray("aud") { aud.forEach { add(it) } }
            put("iat", now)
            put("exp", now + expSeconds)
            if (azp != null) put("azp", azp)
            if (roles != null) putJsonArray("roles") { roles.forEach { add(it) } }
            if (realmAccessRoles != null) {
                putJsonObject("realm_access") {
                    putJsonArray("roles") { realmAccessRoles.forEach { add(it) } }
                }
            }
            if (adminPanelRoles != null) {
                putJsonObject("resource_access") {
                    putJsonObject("admin-panel") {
                        putJsonArray("roles") { adminPanelRoles.forEach { add(it) } }
                    }
                }
            }
        }.toString()
        val header = """{"alg":"RS256","typ":"JWT","kid":"$kid"}"""
        val signingInput = "${b64(header.toByteArray())}.${b64(payload.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(signingInput.toByteArray())
            sign()
        }
        return "$signingInput.${b64(signature)}"
    }

    companion object {
        private fun b64(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private const val RbacAuthTestIssuer = "https://example.invalid/realms/test"

private fun jwkOf(keyId: String, publicKey: RSAPublicKey): Jwk {
    val values = mutableMapOf<String, Any>(
        "kid" to keyId,
        "kty" to "RSA",
        "alg" to "RS256",
        "use" to "sig",
        "n" to Base64.getUrlEncoder().withoutPadding()
            .encodeToString(publicKey.modulus.toByteArray().trimLeadingZero()),
        "e" to Base64.getUrlEncoder().withoutPadding()
            .encodeToString(publicKey.publicExponent.toByteArray().trimLeadingZero())
    )
    return Jwk.fromValues(values)
}

/** BigInteger.toByteArray() が先頭に符号バイトを付けるため除去する。 */
private fun ByteArray.trimLeadingZero(): ByteArray {
    val firstNonZero = indexOfFirst { it != 0.toByte() }
    return if (firstNonZero > 0) copyOfRange(firstNonZero, size) else this
}
