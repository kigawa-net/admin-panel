package net.kigawa.admin.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwk.UrlJwkProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.net.URL

/**
 * issue #183: Keycloak client role(admin-panel)によるRBAC認可。
 *
 * - 認証は Ktor Authentication/JWT(JWKS・iss・exp・aud/admin-panel・必要時 azp)
 * - 認可は各routeの [requireRole] で行い、未認証は401・権限不足は403
 * - ロールの包含(admin ⊃ operator ⊃ viewer)はKeycloak側のcomposite roleで解決済み。
 *   念のため本側でも階層を展開して集合化する(set判定だけで済む)
 */

/** 未ログイン/ロールなしでも運用を切り出せるよう緊急回避用のフォールバック开关(env)。 */
private const val RBAC_ENFORCED_ENV = "KEYCLOAK_RBAC_ENFORCED"

/** kigawa-net realmのJWKS(issue #155移行済みのrealm)。 */
const val DEFAULT_KEYCLOAK_JWKS_URL =
    "https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/certs"

/** access tokenの `aud` に必要な値(IssueのAudience Mapperが付与する)。 */
private const val DEFAULT_AUDIENCE = "admin-panel"

/** tokenのロール名 → AdminRole。 */
private val ROLE_NAMES = mapOf(
    "viewer" to AdminRole.VIEWER,
    "operator" to AdminRole.OPERATOR,
    "admin" to AdminRole.ADMIN
)

/** 認可に使うロール(issue #183のRole定義)。 */
enum class AdminRole {
    VIEWER, OPERATOR, ADMIN
}

/** 検証済みトークンから作る認証済みユーザー(issue #183のPrincipal)。 */
data class AdminPrincipal(
    val userId: String,
    val roles: Set<AdminRole>
) {
    /** [required] 以上の権限を持つか。ロールの包含は集合に展開済みなので集合判定で足りる。 */
    fun hasRole(required: AdminRole): Boolean = roles.contains(required)
}

/**
 * RBAC設定(env)。既定は kigawa-net realm を指す。
 * - `KEYCLOAK_JWKS_URL`: JWKS(certs)のURL。鍵ローテーションはこの先の鍵更新で行われる
 * - `KEYCLOAK_ISSUER`: 検証するiss。未指定時はJWKS URLから推定(…/protocol/openid-connect/certs を除去)
 * - `KEYCLOAK_AUDIENCE`: 検証するaud。既定 admin-panel
 * - `KEYCLOAK_AZP`: 指定時のみ azp を要求する(任意要件)
 * - `KEYCLOAK_RBAC_ENFORCED`: 既定 true。false のときだけ緊急回避として
 *   「ロール非依存=ログインのみでadmin判定」の旧来どおりの挙動にフォールバックする
 */
data class RbacConfig(
    val jwksUrl: String,
    val issuer: String,
    val audience: String,
    val azp: String?,
    val rbacEnforced: Boolean
) {
    companion object {
        fun fromEnv(): RbacConfig = fromEnv(System::getenv)

        /** テスト用に env取得関数を差し替えられる版。 */
        fun fromEnv(env: (String) -> String?): RbacConfig {
            val jwksUrl = env("KEYCLOAK_JWKS_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_KEYCLOAK_JWKS_URL
            val issuer = env("KEYCLOAK_ISSUER")?.takeIf { it.isNotBlank() } ?: issuerFromJwksUrl(jwksUrl)
            val audience = env("KEYCLOAK_AUDIENCE")?.takeIf { it.isNotBlank() } ?: DEFAULT_AUDIENCE
            val azp = env("KEYCLOAK_AZP")?.takeIf { it.isNotBlank() }
            // 未指定・空は既定の厳格側(true)。false/0/no のときだけ緊急回避。
            val enforced = !when (env(RBAC_ENFORCED_ENV)?.trim()?.lowercase()) {
                "false", "0", "no", "off" -> true
                else -> false
            }
            return RbacConfig(jwksUrl, issuer, audience, azp, enforced)
        }

        /** JWKS URLからiss(realmルート)を推定する。 */
        fun issuerFromJwksUrl(jwksUrl: String): String =
            jwksUrl.removeSuffix("/").removeSuffix("/protocol/openid-connect/certs")
    }
}

/**
 * JWKSの取得キャッシュと鍵ローテーション対応の [JwkProvider](issue #183)。
 *
 * - 初回取得後は [cacheTtlMillis] の間キャッシュを使い、検証のたびにHTTPへ行かない
 * - キャッシュ期間中の未知のkid(ローテーション直後の新鍵)は、[minRefreshIntervalMillis] を
 *   空けない範囲で即再取得して対応する(乱造トークンによる再取得スパムの防止)
 * - 再取得に失敗しても取得済みのキャッシュは破棄せず(stale許容)検証を続行する。
 *   キャッシュが1件も無い状態で取得に失敗した場合は [SigningKeyNotFoundException] を投げ、
 *   Ktor側の認証失敗処理により401になる(500にはしない)
 */
class RefreshingJwkProvider(
    private val jwksUrl: String,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val minRefreshIntervalMillis: Long = DEFAULT_MIN_REFRESH_INTERVAL_MILLIS,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** テスト用に取得元を差し替える。本番は null で [jwksUrl] から取得する。 */
    private val fetchOverride: (() -> List<Jwk>)? = null
) : JwkProvider {

    private data class CachedKeys(val keys: List<Jwk>, val fetchedAt: Long)

    private val logger: Logger = LoggerFactory.getLogger(RefreshingJwkProvider::class.java)

    @Volatile
    private var cached: CachedKeys? = null

    @Volatile
    private var lastAttemptAt: Long = 0L

    private val refreshLock = Any()

    override fun get(keyId: String?): Jwk {
        val now = System.currentTimeMillis()
        val stale = cached
        if (stale == null || now - stale.fetchedAt >= cacheTtlMillis) {
            refreshByTtl()
        }
        val snapshot = cached
            ?: throw SigningKeyNotFoundException("JWKSを取得できない: $jwksUrl", null)
        findByKid(snapshot.keys, keyId)?.let { return it }

        // 未知のkid → 鍵ローテーション直後を疑って再取得(最小間隔の範囲のみ)
        if (refreshQuietly()) {
            findByKid(cached?.keys ?: emptyList(), keyId)?.let { return it }
        }
        throw SigningKeyNotFoundException("kid=$keyId がJWKSに存在しない: $jwksUrl", null)
    }

    /** 起動時確認用。JWKSを取得して(キャッシュして)kid一覧を返す。失敗時は例外を投げる。 */
    fun warmUp(): List<String> = doFetch().map { it.id ?: "(kidなし)" }

    /** kid一致(未指定kidは鍵が1つだけのときのみ)で鍵を取り出す。 */
    private fun findByKid(keys: List<Jwk>, keyId: String?): Jwk? = when {
        keyId != null -> keys.firstOrNull { it.id == keyId }
        keys.size == 1 -> keys.firstOrNull()
        else -> null
    }

    /** TTL切れ(または未取得)なら再取得する。 */
    private fun refreshByTtl() {
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            val current = cached
            if (current != null && now - current.fetchedAt < cacheTtlMillis) return // 他スレッドが取得済み
            lastAttemptAt = now
            try {
                cached = CachedKeys(doFetch(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失敗しても既存のキャッシュは破棄しない(staleを許容して検証を続行)
                logger.warn("JWKS取得失敗(既存キャッシュを継続使用): url=$jwksUrl", e)
            }
        }
    }

    /** TTLと無関係に、一定間隔を空けて再取得を試みる。取得できたら true。 */
    private fun refreshQuietly(): Boolean {
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            if (now - lastAttemptAt < minRefreshIntervalMillis) return false
            lastAttemptAt = now
            return try {
                cached = CachedKeys(doFetch(), now)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("JWKS再取得失敗: url=$jwksUrl", e)
                false
            }
        }
    }

    private fun doFetch(): List<Jwk> = fetchOverride?.invoke()
        ?: UrlJwkProvider(createUrlWithCustomSSL(jwksUrl), connectTimeoutMillis, connectTimeoutMillis).getAll()

    /**
     * JWKS取得用のURLを作成し、内部通信用にSSL証明書検証をスキップするカスタムSSLContextを設定する。
     * 内部クラスタ通信ではKeycloakの自己署名証明書を信頼するため、ホスト名検証を無効化する。
     */
    private fun createUrlWithCustomSSL(urlString: String): URL {
        val url = java.net.URI(jwksUrl).toURL()
        // 内部クラスタ通信ではSSL証明書検証をスキップする（Keycloakの自己署名証明書を信頼）
        // 注意: 本番環境では適切な証明書管理を行うこと
        if (url.protocol == "https" && (url.host.contains("keycloak") || url.host.contains("user.kigawa.net"))) {
            // デフォルトのSSLContextを一度だけ置き換える
            if (!SSLContext.getDefault().toString().contains("InsecureSSLContext")) {
                try {
                    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    })
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                    HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
                } catch (e: Exception) {
                    logger.warn("カスタムSSLContextの設定に失敗しました（デフォルトを使用します）", e)
                }
            }
        }
        return url
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_MIN_REFRESH_INTERVAL_MILLIS = 30 * 1000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
    }
}

/**
 * Ktor Authentication(JWT)を設定する(issue #183)。
 *
 * 検証対象は issue 記載のとおり:
 * - signature / JWKS([jwkProvider]。既定は [RefreshingJwkProvider])
 * - `iss`
 * - `exp`(java-jwtの既定検証)
 * - `aud`([RbacConfig.audience]、RBAC無効化モード時のみ省略)
 * - `azp`([RbacConfig.azp]、指定時のみ)
 *
 * 検証に成功したトークンからは [AdminPrincipal] を作り、route側は [requireRole] で判定する。
 */
fun AuthenticationConfig.keycloakJwt(
    rbac: RbacConfig,
    jwkProvider: JwkProvider = RefreshingJwkProvider(rbac.jwksUrl)
) {
    jwt("keycloak") {
        realm = "admin-panel"
        verifier(jwkProvider, rbac.issuer) {
            // 緊急回避モード(KEYCLOAK_RBAC_ENFORCED=false)ではaud要件も外す。
            // それ以外は issue のとおり access token の aud に admin-panel を要求する。
            if (rbac.rbacEnforced) withAudience(rbac.audience)
        }
        validate { credential ->
            val sub = credential.subject
            if (sub.isNullOrBlank()) {
                // subの無いトークンは組織スコープ判定に使えないため受け付けない
                return@validate null
            }
            val azp = rbac.azp
            if (azp != null && credential["azp"] != azp) {
                return@validate null
            }
            AdminPrincipal(sub, rolesFor(credential, rbac))
        }
        // 認証失敗は全て401(未認証扱い)。本文は従来の形式を踏襲する。
        challenge { _, _ ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
        }
    }
}

/** トークンのロールから [AdminPrincipal] 用のロール集合を作る。 */
private fun rolesFor(credential: JWTCredential, rbac: RbacConfig): Set<AdminRole> {
    if (!rbac.rbacEnforced) {
        // 緊急回避: Keycloak側のロール整備(k8s-system側Job)が未適用の環境向けに、
        // 旧来どおり「ログインできれば admin」とする。既定では無効(true=厳格)。
        return AdminRole.entries.toSet()
    }
    return adminRolesFrom(readRoleNames(credential))
}

/**
 * access tokenのロールクレームを読む(issue #183)。
 * 主は issue の記載どおり `resource_access["admin-panel"].roles`、
 * 以下をフォールバックとして併せて集合に含める:
 * - トップレベル `roles`(KeycloakのUser Client Roleマッパー(multivalued)が付与する形式)
 * - `realm_access.roles`(クレームの載り方を揺らす構成への互換)
 * ロール名はクライアント/レルムをまたいで同じ名前(viewer/operator/admin)に限られるため、
 * いずれの形式でも同じ [AdminRole] に写像できる。
 */
internal fun readRoleNames(credential: JWTCredential): Set<String> {
    val raw = mutableSetOf<String>()
    for (claimName in listOf("roles", "realm_access")) {
        try {
            when (val claim = credential.payload.getClaim(claimName)) {
                null -> Unit
                else -> {
                    // トップレベル roles は配列、realm_access は { roles: [...] } のオブジェクト
                    val names = claim.`as`(Any::class.java)
                    when (names) {
                        is Collection<*> -> names.filterIsInstance<String>().forEach { raw += it }
                        is Map<*, *> -> (names["roles"] as? Collection<*>)
                            ?.filterIsInstance<String>()?.forEach { raw += it }
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            // 予期しない型のクレームは無視する(安全側=ロールなし)
        }
    }
    try {
        @Suppress("UNCHECKED_CAST")
        val resourceAccess = credential.payload.getClaim("resource_access").`as`(Map::class.java) as? Map<*, *>
        val adminPanelRoles = (resourceAccess?.get("admin-panel") as? Map<*, *>)?.get("roles")
        if (adminPanelRoles is Collection<*>) {
            adminPanelRoles.filterIsInstance<String>().forEach { raw += it }
        }
    } catch (e: Exception) {
        // 形式が未知の場合は無視する(安全側=ロールなし)
    }
    return raw
}

/**
 * ロール名集合を [AdminRole] 集合へ写像し、包含関係を展開する。
 * composite roleで admin ⊃ operator ⊃ viewer はKeycloak側で解決済みだが、
 * 環境差で展開されていないトークンでもマトリクス通りになるよう本側でも広げる。
 */
fun adminRolesFrom(rawRoleNames: Collection<String>): Set<AdminRole> {
    val named = rawRoleNames.mapNotNull { ROLE_NAMES[it] }.toSet()
    // 上位ロールは下位ロールも併せて持つ(包含関係の展開)
    val hasAdmin = AdminRole.ADMIN in named
    val hasOperator = hasAdmin || AdminRole.OPERATOR in named
    val hasViewer = hasOperator || AdminRole.VIEWER in named
    return buildSet {
        if (hasViewer) add(AdminRole.VIEWER)
        if (hasOperator) add(AdminRole.OPERATOR)
        if (hasAdmin) add(AdminRole.ADMIN)
    }
}

/**
 * route単位の認可ヘルパー(issue #183)。
 *
 * - 未認証(401): JWT検証を通過していない(= 認証ヘッダが無い/無効な)場合
 * - 権限不足(403): 認証済みだが [required] 以上のロールを持たない場合
 *
 * 認可に成功したら [AdminPrincipal] を返し、失敗時はレスポンスを送出して null を返す。
 * 呼び出し側は `val principal = requireRole(AdminRole.VIEWER) ?: return@get` の形で抜ける。
 * 実際の拒否レスポンスは `authenticate("keycloak")` 側でも送出されるが、
 * プリンシパル不在時の保険として本ヘルパー側でも401を返す。
 */
suspend fun RoutingContext.requireRole(required: AdminRole): AdminPrincipal? {
    val principal = call.principal<AdminPrincipal>()
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
        return null
    }
    if (!principal.hasRole(required)) {
        auditLog(
            principal,
            operation = "authorize",
            target = "${call.request.httpMethod.value} ${call.request.path()}",
            result = "denied",
            detail = "required=${required.name.lowercase()}"
        )
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "insufficient role (requires ${required.name.lowercase()} or higher)")
        )
        return null
    }
    return principal
}

/**
 * 起動時にRBAC設定とJWKS到達性をログへ残す(issue #183)。
 * 「ロール判定が可能か」(JWKSに到達できるか・aud要件が設定されているか)を起動ログで確認できるようにする。
 * JWKS取得はバックグラウンドで行い、失敗しても起動は継続する(初回リクエスト時に再試行される)。
 */
fun logRbacStartupStatus(
    rbac: RbacConfig,
    provider: RefreshingJwkProvider,
    logger: Logger,
    scope: CoroutineScope
) {
    logger.info(
        "RBAC認証設定: jwks={}, issuer={}, audience={}, azp={}, rbacEnforced={}",
        rbac.jwksUrl,
        rbac.issuer,
        rbac.audience,
        rbac.azp ?: "(未指定)",
        rbac.rbacEnforced
    )
    if (rbac.rbacEnforced) {
        logger.info(
            "ロール判定要件: access token の aud に '{}' が必要。ロール未整備の環境では403になるため " +
                "Keycloak側(client role/audience mapper)の適用を先に行うこと",
            rbac.audience
        )
    } else {
        logger.warn(
            "{}=false: RBAC無効化モード。ロール判定に依存せず、ログインした全ユーザーをadmin扱いにする" +
                "緊急回避用(既定はtrue=厳格)",
            RBAC_ENFORCED_ENV
        )
    }
    scope.launch {
        try {
            val kids = withContext(Dispatchers.IO) { provider.warmUp() }
            logger.info("JWKS到達確認: OK ({} keys, kid={})", kids.size, kids.joinToString(","))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(
                "JWKS到達確認: 失敗 ({}). 起動は継続し、検証はリクエスト毎に再試行する(到達不能なら401)",
                e.message ?: e.javaClass.simpleName
            )
        }
    }
}
