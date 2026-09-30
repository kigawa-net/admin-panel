package net.kigawa.admin.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.await
import net.kigawa.admin.util.URLSearchParams
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.js.Promise

/**
 * Keycloakのkigawa-netレルムでの認証を扱う。
 * issue #155で管理用realm(manage)からkigawa-netへ移行した。kigawa-net realmは
 * 誰でもセルフ登録できる(registrationAllowed=true)ため、管理者判定はuserinfoに載る
 * ロール(admin-panelクライアントの admin ロール)の有無で行い、
 * ロールが確認できない場合は非管理者(安全側)として扱う。
 * 実際のアクセス制御の境界はサーバー側(RBAC)でも併せて行う。
 *
 * issue #169でGoogle式アカウント切替えに対応し、複数ユーザーのセッションを
 * 同時保持できる。アカウントIDはuserinfoのsubを用いる。
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("id_token") val idToken: String? = null
)

@Serializable
data class UserInfoResponse(
    @SerialName("sub") val sub: String,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("name") val name: String? = null,
    // ロールはKeycloakのマッパー設定により形式が揺れるため、あえて型を固定しない
    @SerialName("roles") val roles: JsonElement? = null,
    @SerialName("realm_access") val realmAccess: JsonElement? = null,
    @SerialName("resource_access") val resourceAccess: JsonElement? = null
)

/**
 * userinfoのロール表現に admin が含まれるか判定する。
 * KeycloakのUser Client Roleマッパー(multivalued)はトップレベルの roles に
 * 配列("admin"等)で出る想定だが、realm_access.roles / resource_access["admin-panel"].roles
 * 形式にも対応するため、配列・オブジェクト・文字列のいずれでも受ける。
 * ロールが無い・形式が不明な場合は false(非管理者・安全側)にする。
 */
private fun JsonElement?.containsAdminRole(): Boolean = when (this) {
    is JsonArray -> any { it is JsonPrimitive && it.content == "admin" }
    is JsonObject -> keys.any { it == "admin" } || values.any { it.containsAdminRole() }
    is JsonPrimitive -> content == "admin"
    else -> false
}

/**
 * userinfo全体から管理者(adminロール保有)を判定する。
 * kigawa-net realmは誰でもセルフ登録できるため、ロール欠落時を管理者扱いにすると
 * 全員が管理者になってしまう。必ず「ロールが確認できた場合のみ」true を返す。
 */
private fun UserInfoResponse.hasAdminRole(): Boolean {
    if (roles != null && roles.containsAdminRole()) return true
    if (realmAccess != null && realmAccess.containsAdminRole()) return true
    // resource_accessはクライアントIDごとの入れ子なので、admin-panelクライアント分のみ見る
    val adminPanelAccess = (resourceAccess as? JsonObject)?.get("admin-panel")
    return adminPanelAccess != null && adminPanelAccess.containsAdminRole()
}

/** アカウント切替えメニューの表示用。idはuserinfoのsub(レガシー移行直後は仮ID)。 */
data class AccountInfo(val id: String, val username: String)

/** localStorageのkc_accountsにJSONとして保存する全アカウントのセッション束。 */
@Serializable
private data class AccountStore(
    val accounts: Map<String, StoredAccount> = emptyMap()
)

/** AccountStoreに格納する1アカウント分のセッション。 */
@Serializable
private data class StoredAccount(
    val username: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAt: Long = 0L,
    // 最後に userinfo で確認できた管理者フラグ。取得できない間は false(安全側)。
    val isAdmin: Boolean = false
)

object KeycloakConfig {
    val serverUrl: String = js("window.__KEYCLOAK_URL__ || 'https://user.kigawa.net'") as String
    val clientId: String = js("window.__KEYCLOAK_CLIENT_ID__ || 'admin-panel'") as String
    // issue #155で管理用realm(manage)からkigawa-netへ移行した
    val realm: String = "kigawa-net"

    fun authUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/auth"
    fun tokenUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/token"
    fun userInfoUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/userinfo"
    fun logoutUrl() = "$serverUrl/realms/$realm/protocol/openid-connect/logout"
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(
        val username: String,
        val accessToken: String,
        val accountId: String,
        val accounts: List<AccountInfo> = emptyList(),
        // userinfoのロール(admin-panelのadminロール)で判定。確認できない間は false(安全側)
        val isAdmin: Boolean = false
    ) : AuthState()

    data class Error(val message: String) : AuthState()
}

private const val KEY_CODE_VERIFIER = "kc_code_verifier"
private const val KEY_STATE = "kc_state"

/** 複数アカウントのセッション本体(id -> StoredAccountのJSONマップ)。 */
private const val KEY_ACCOUNTS = "kc_accounts"

/** 現在有効なアカウントのID。 */
private const val KEY_ACTIVE_ACCOUNT = "kc_active_account"

// 以下は単一セッション時代の旧キー。初回init時に新形式へ移行して削除する。
private const val KEY_ACCESS_TOKEN = "kc_access_token"
private const val KEY_ID_TOKEN = "kc_id_token"
private const val KEY_REFRESH_TOKEN = "kc_refresh_token"
private const val KEY_EXPIRES_AT = "kc_expires_at"
private const val KEY_USERNAME = "kc_username"

/** レガシー移行で使う仮IDの接頭辞。正規のsubが分かるまで使う。 */
private const val LEGACY_ID_PREFIX = "legacy-"

/** Refresh this long before actual expiry, to allow for request latency and clock skew. */
private const val REFRESH_MARGIN_MS = 30_000L

/** init()の時点で「期限切れか、まもなく期限切れ」とみなす猶予(issue #184)。
 * API往復の遅延とクライアント/サーバー間の時計誤差を見るため、
 * 自動リフレッシュ用のREFRESH_MARGIN_MS(30秒)より広めに60秒取る。 */
private const val INIT_REFRESH_MARGIN_MS = 60_000L

private fun nowMillis(): Long = (js("Date.now()") as Double).toLong()

private val accountsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun generateRandom(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val array = js("new Uint8Array(length)")
    js("crypto.getRandomValues(array)")
    val sb = StringBuilder(length)
    repeat(length) { i ->
        sb.append(chars[(array[i] as Int) % chars.length])
    }
    return sb.toString()
}

private suspend fun sha256Base64Url(input: String): String {
    val encoder: dynamic = js("new TextEncoder()")
    val data: dynamic = encoder.encode(input)
    @Suppress("UNCHECKED_CAST")
    val hashBuffer = (js("crypto.subtle.digest('SHA-256', data)") as Promise<dynamic>).await()
    val hashArray: dynamic = js("Array.from(new Uint8Array(hashBuffer))")
    val base64 = js("btoa(String.fromCharCode.apply(null, hashArray))") as String
    return base64
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')
}

private fun displayNameOf(userInfo: UserInfoResponse): String =
    userInfo.name
        ?: userInfo.preferredUsername
        ?: userInfo.email
        ?: "User"

class KeycloakAuthProvider : AutoCloseable {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val httpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var refreshJob: Job? = null

    fun setError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    fun init() {
        migrateLegacyIfNeeded()
        val accounts = loadAccounts()
        if (accounts.isEmpty()) {
            _authState.value = AuthState.Unauthenticated
            return
        }
        emitActiveAccountOrRefresh {
            // 自動更新は初回リフレッシュの完了後に始める。先に走らせると
            // 期限前の自動リフレッシュと二重リクエストになる(issue #184)。
            scheduleAutoRefresh()
        }
    }

    /** 保存済みトークンを、追加のリフレッシュなしでそのままAPIへ送れてよいか判定する。
     * 期限切れ・まもなく期限切れ(INIT_REFRESH_MARGIN_MS以内)・期限不明(expiresAt=0、
     * 古いデータ等)は false とし、先にリフレッシュを試みる。 */
    private fun isTokenUsableWithoutRefresh(account: StoredAccount): Boolean {
        if (account.expiresAt <= 0L) return false
        return account.expiresAt - nowMillis() > INIT_REFRESH_MARGIN_MS
    }

    /**
     * 有効アカウントの状態を流す。保存済みトークンが使える間は従来どおり即座に
     * Authenticated を流し、期限切れ(またはまもなく期限切れ・期限不明)の場合は
     * Authenticated を先に流さない(issue #184)。期限切れトークンを先にAPIへ送ると
     * 描画と同時に401になるため、Loading のまま先にリフレッシュを試み、
     * 成功時にのみ新しいトークンで Authenticated を流す。
     * リフレッシュ失敗時のみ handleRefreshFailure() へ回し、残アカウントがあれば
     * 先頭を有効化して継続、なければログイン要求(エラー表示)へ落とす。
     * [onSettled] は状態が流れた後に呼ぶ(自動更新スケジュールの開始など)。
     * リフレッシュ成功時は userinfo 取得を併せて行うため、管理者フラグの再取得はしない。
     */
    private fun emitActiveAccountOrRefresh(onSettled: () -> Unit) {
        val resolved = resolveActiveAccount()
        if (resolved == null) {
            // アカウントが無い。未認証として流す(呼出し側では空判定済みだが安全側)。
            emitActiveOrUnauthenticated()
            onSettled()
            return
        }
        if (isTokenUsableWithoutRefresh(resolved.second)) {
            // 有効な保存済みトークンがあれば、従来どおり即座に認証済みを流す
            emitActiveOrUnauthenticated()
            // localStorageにはロール情報が最新で入っていない場合があるため、
            // userinfo から管理者フラグを最新化する(失敗時は現状維持)
            refreshActiveAdminFlag()
            onSettled()
            return
        }
        // 期限切れトークンをAPIへ送って401にする前に、先にリフレッシュを試す。
        _authState.value = AuthState.Loading
        scope.launch {
            if (!refreshActiveAccount()) {
                // 失敗時のみ既存の失効処理へ回す。残アカウントがあれば先頭を有効化して
                // 継続し、なければログイン要求(エラー表示)へ落とす。
                handleRefreshFailure()
                // 切替先アカウントの管理者フラグは userinfo で最新化する(失敗時は現状維持)
                refreshActiveAdminFlag()
            }
            onSettled()
        }
    }

    /**
     * 保持しているアクセストークンで userinfo を取得し、管理者フラグを最新化する。
     * 保存時点のロールが古い(権限が剥奪された等)場合に備えた補完で、
     * 取得に失敗してもログイン状態は維持し、描画クラッシュもさせない。
     */
    private fun refreshActiveAdminFlag() {
        scope.launch {
            try {
                val activeId = try {
                    localStorage[KEY_ACTIVE_ACCOUNT]
                } catch (e: Throwable) {
                    null
                } ?: return@launch
                val account = loadAccounts()[activeId] ?: return@launch
                val userInfo = httpClient.get(KeycloakConfig.userInfoUrl()) {
                    bearerAuth(account.accessToken)
                }.body<UserInfoResponse>()
                val admin = userInfo.hasAdminRole()
                // 取得中に別の処理が書き換えていたら触らない
                val latest = loadAccounts()
                val current = latest[activeId] ?: return@launch
                if (current.isAdmin == admin && !activeId.startsWith(LEGACY_ID_PREFIX)) return@launch
                latest[activeId] = current.copy(
                    isAdmin = admin,
                    username = if (activeId.startsWith(LEGACY_ID_PREFIX)) displayNameOf(userInfo) else current.username
                )
                saveAccounts(latest)
                emitActiveOrUnauthenticated()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ktor-client-jsがブラウザのfetch()失敗時にExceptionをすり抜けて
                // 投げる場合があるためThrowableで受ける。失敗時は現状維持する。
            }
        }
    }

    /**
     * 単一セッション時代の旧キーが残っていれば新形式(kc_accounts/kc_active_account)へ移行する。
     * 移行時点ではuserinfo取得が同期でできないため、正規のsubではなく
     * 仮ID(legacy-<username>)で保存し、次回リフレッシュ/再ログインで正規subに正規化する。
     */
    private fun migrateLegacyIfNeeded() {
        try {
            if (localStorage[KEY_ACCOUNTS] != null) return
            val token = localStorage[KEY_ACCESS_TOKEN] ?: return
            val username = localStorage[KEY_USERNAME] ?: return
            val legacyId = "$LEGACY_ID_PREFIX$username"
            val accounts = mutableMapOf(
                legacyId to StoredAccount(
                    username = username,
                    accessToken = token,
                    refreshToken = localStorage[KEY_REFRESH_TOKEN],
                    idToken = localStorage[KEY_ID_TOKEN],
                    expiresAt = localStorage[KEY_EXPIRES_AT]?.toLongOrNull() ?: 0L
                )
            )
            saveAccounts(accounts)
            localStorage[KEY_ACTIVE_ACCOUNT] = legacyId
            // 旧キーを削除し、二重管理にしない
            localStorage.removeItem(KEY_ACCESS_TOKEN)
            localStorage.removeItem(KEY_ID_TOKEN)
            localStorage.removeItem(KEY_REFRESH_TOKEN)
            localStorage.removeItem(KEY_EXPIRES_AT)
            localStorage.removeItem(KEY_USERNAME)
        } catch (e: Throwable) {
            // 移行失敗時は旧キーを残し、次回init時に再試行する
        }
    }

    /** localStorageから全アカウントを読み出す。破損時は空扱いにする。 */
    private fun loadAccounts(): MutableMap<String, StoredAccount> {
        val raw = try {
            localStorage[KEY_ACCOUNTS]
        } catch (e: Throwable) {
            // localStorage自体が使えない(プライベートモード等)場合は空扱い
            return mutableMapOf()
        } ?: return mutableMapOf()
        return try {
            accountsJson.decodeFromString(AccountStore.serializer(), raw).accounts.toMutableMap()
        } catch (e: Throwable) {
            // 保存形式の破損で描画クラッシュさせないため、空扱いにする
            mutableMapOf()
        }
    }

    private fun saveAccounts(accounts: Map<String, StoredAccount>) {
        try {
            if (accounts.isEmpty()) {
                localStorage.removeItem(KEY_ACCOUNTS)
            } else {
                localStorage[KEY_ACCOUNTS] = accountsJson.encodeToString(AccountStore.serializer(), AccountStore(accounts))
            }
        } catch (e: Throwable) {
            // 保存失敗時は次回読み出しで空扱いになる。描画クラッシュはさせない。
        }
    }

    /**
     * 有効アカウントを解決する。指定が壊れていれば先頭を有効化して保存し直す。
     * アカウントが1件も無ければ null。
     */
    private fun resolveActiveAccount(): Pair<String, StoredAccount>? {
        val accounts = loadAccounts()
        if (accounts.isEmpty()) return null
        var activeId = try {
            localStorage[KEY_ACTIVE_ACCOUNT]
        } catch (e: Throwable) {
            null
        }
        var active = if (activeId != null) accounts[activeId] else null
        if (active == null || activeId == null) {
            // 有効アカウント指定が壊れている場合は先頭を有効化する
            activeId = accounts.keys.first()
            active = accounts[activeId]
            try {
                localStorage[KEY_ACTIVE_ACCOUNT] = activeId
            } catch (e: Throwable) {
                // 保存失敗時は今回の表示だけ有効化する
            }
        }
        // 上の分岐で activeId/active は必ず非nullになる(コンパイラもこれを認識する)
        return if (active != null) activeId to active else null
    }

    /** 有効アカウントの状態を流す。指定が壊れていれば先頭を有効化し、空なら未認証にする。 */
    private fun emitActiveOrUnauthenticated() {
        val accounts = loadAccounts()
        val resolved = resolveActiveAccount()
        if (resolved != null) {
            val (activeId, active) = resolved
            _authState.value = AuthState.Authenticated(
                username = active.username,
                accessToken = active.accessToken,
                accountId = activeId,
                accounts = accounts.map { (id, account) -> AccountInfo(id, account.username) },
                isAdmin = active.isAdmin
            )
        } else {
            try {
                localStorage.removeItem(KEY_ACTIVE_ACCOUNT)
            } catch (e: Throwable) {
                // 無視
            }
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Keeps the access token alive for as long as this page stays open and the refresh token
     * remains valid, since Keycloak access tokens are short-lived (commonly ~5 minutes) and this
     * app has no other mechanism to renew them. Each page navigation creates a fresh provider
     * (and thus a fresh loop), so this only needs to cover a single page's lifetime.
     * 複数アカウントのうち、有効なアカウントのみをリフレッシュ対象とする。
     */
    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                val activeId = try {
                    localStorage[KEY_ACTIVE_ACCOUNT]
                } catch (e: Throwable) {
                    null
                } ?: break
                val expiresAt = loadAccounts()[activeId]?.expiresAt
                val delayMs = if (expiresAt != null && expiresAt > 0) {
                    (expiresAt - nowMillis() - REFRESH_MARGIN_MS).coerceAtLeast(0L)
                } else {
                    0L
                }
                delay(delayMs)
                if (!refreshActiveAccount()) {
                    if (!handleRefreshFailure()) break
                }
            }
        }
    }

    /** 有効アカウントのリフレッシュに失敗したら、そのアカウントだけを破棄する。
     * 他のアカウントが残っていれば次を有効化して継続(true)し、
     * なければ再ログインを促す(falseでループ終了)。 */
    private fun handleRefreshFailure(): Boolean {
        val accounts = loadAccounts()
        val failedId = try {
            localStorage[KEY_ACTIVE_ACCOUNT]
        } catch (e: Throwable) {
            null
        }
        if (failedId != null) accounts.remove(failedId)
        saveAccounts(accounts)
        if (accounts.isNotEmpty()) {
            try {
                localStorage[KEY_ACTIVE_ACCOUNT] = accounts.keys.first()
            } catch (e: Throwable) {
                // 保存失敗時は今回の表示だけ有効化する
            }
            emitActiveOrUnauthenticated()
            return true
        }
        try {
            localStorage.removeItem(KEY_ACTIVE_ACCOUNT)
        } catch (e: Throwable) {
            // 無視
        }
        _authState.value = AuthState.Error("セッションの有効期限が切れました。再度ログインしてください。")
        return false
    }

    /** Exchanges the stored refresh token for a new access token. Returns false if that fails
     * (refresh token expired/revoked), leaving the current, likely-now-stale access token in
     * place until the user next has to log in again. */
    private suspend fun refreshActiveAccount(): Boolean {
        val activeId = try {
            localStorage[KEY_ACTIVE_ACCOUNT]
        } catch (e: Throwable) {
            null
        } ?: return false
        val account = loadAccounts()[activeId] ?: return false
        val refreshToken = account.refreshToken ?: return false
        try {
            val tokenResponse = httpClient.submitForm(
                url = KeycloakConfig.tokenUrl(),
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", KeycloakConfig.clientId)
                    append("refresh_token", refreshToken)
                }
            ).body<TokenResponse>()

            // 処理中に切替え/削除が起きていたら、古い結果で上書きしない
            val currentActiveId = try {
                localStorage[KEY_ACTIVE_ACCOUNT]
            } catch (e: Throwable) {
                null
            }
            if (currentActiveId != activeId) return true

            var updated = account.copy(
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken ?: account.refreshToken,
                idToken = tokenResponse.idToken ?: account.idToken,
                expiresAt = nowMillis() + tokenResponse.expiresIn * 1000L
            )
            // リフレッシュ成功時に userinfo から管理者フラグを最新化する。
            // ロールが載らない・取得失敗の場合は保存済みの値を維持する(安全側は
            // 「非管理者」だが、表示用フラグはサーバー側のRBACでも再判定される)。
            // 併せてレガシー仮ID(legacy-*)の正規化も行う。
            var nextActiveId = activeId
            try {
                val userInfo = httpClient.get(KeycloakConfig.userInfoUrl()) {
                    bearerAuth(tokenResponse.accessToken)
                }.body<UserInfoResponse>()
                updated = updated.copy(
                    isAdmin = userInfo.hasAdminRole(),
                    username = displayNameOf(userInfo)
                )
                if (activeId.startsWith(LEGACY_ID_PREFIX)) {
                    nextActiveId = userInfo.sub
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ktor-client-jsがブラウザのfetch()失敗時にExceptionをすり抜けて
                // 投げる場合があるためThrowableで受ける(既存の描画クラッシュ対策と同様)。
                // userinfo取得に失敗しても、トークン更新自体は有効なので継続する。
            }

            val next = loadAccounts()
            next.remove(activeId)
            next[nextActiveId] = updated
            saveAccounts(next)
            try {
                localStorage[KEY_ACTIVE_ACCOUNT] = nextActiveId
            } catch (e: Throwable) {
                // 保存失敗時は今回の表示だけ有効化する
            }
            emitActiveOrUnauthenticated()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // ktor-client-jsがブラウザのfetch()失敗時にExceptionをすり抜けて
            // 投げる場合があるためThrowableで受ける(既存の描画クラッシュ対策と同様)。
            return false
        }
    }

    /**
     * ログイン開始。既存セッションを消さないため、そのまま「別のアカウントを追加」
     * としても使える。コールバック側(handleCallback)でupsert+有効化する。
     * forceLogin=trueの場合は認可URLにprompt=loginを付与し、KeycloakのSSOによる
     * 無言ログインを抑止して別ユーザーでの再認証を強制する。
     */
    suspend fun startLogin(forceLogin: Boolean = false) {
        val codeVerifier = generateRandom(128)
        val state = generateRandom(32)
        val codeChallenge = sha256Base64Url(codeVerifier)

        localStorage[KEY_CODE_VERIFIER] = codeVerifier
        localStorage[KEY_STATE] = state

        val redirectUri = "${window.location.origin}/callback"
        val params = URLSearchParams()
        params.set("response_type", "code")
        params.set("client_id", KeycloakConfig.clientId)
        params.set("redirect_uri", redirectUri)
        // offline_accessスコープを要求すると、通常のSSOセッション(ブラウザを長時間
        // 閉じていると期限切れになりやすい)より大幅に長寿命な「オフラインリフレッシュ
        // トークン」がKeycloakから発行される。しばらく間を置いてから再訪すると再ログインを
        // 求められる、という報告(admin-panel#115)はこのリフレッシュトークンの期限切れが
        // 原因であり、offline_accessを要求することでセッション保持期間を延ばせる。
        params.set("scope", "openid profile email offline_access")
        params.set("state", state)
        params.set("code_challenge", codeChallenge)
        params.set("code_challenge_method", "S256")
        if (forceLogin) params.set("prompt", "login")

        window.location.href = "${KeycloakConfig.authUrl()}?$params"
    }

    /**
     * コールバックで受けた認可コードをトークンに交換し、アカウント一覧へupsertして有効化する。
     * 同一subでの再ログインは置換され、アカウントが増殖しない。
     */
    suspend fun handleCallback(code: String, state: String) {
        _authState.value = AuthState.Loading

        val savedState = localStorage[KEY_STATE]
        if (state != savedState) {
            _authState.value = AuthState.Error("Invalid state parameter")
            return
        }

        val codeVerifier = localStorage[KEY_CODE_VERIFIER]
        if (codeVerifier == null) {
            _authState.value = AuthState.Error("Missing code verifier")
            return
        }

        localStorage.removeItem(KEY_CODE_VERIFIER)
        localStorage.removeItem(KEY_STATE)

        try {
            val redirectUri = "${window.location.origin}/callback"
            val tokenResponse = httpClient.submitForm(
                url = KeycloakConfig.tokenUrl(),
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("client_id", KeycloakConfig.clientId)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                }
            ).body<TokenResponse>()

            val userInfo = httpClient.get(KeycloakConfig.userInfoUrl()) {
                bearerAuth(tokenResponse.accessToken)
            }.body<UserInfoResponse>()

            val displayName = displayNameOf(userInfo)
            val accountId = userInfo.sub

            val accounts = loadAccounts()
            accounts[accountId] = StoredAccount(
                username = displayName,
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken ?: accounts[accountId]?.refreshToken,
                idToken = tokenResponse.idToken,
                expiresAt = nowMillis() + tokenResponse.expiresIn * 1000L,
                // kigawa-net realmは誰でもセルフ登録できるため、ロールが確認できた
                // 場合のみ管理者とする(無い間は非管理者・安全側)
                isAdmin = userInfo.hasAdminRole()
            )
            // 同一ユーザーのレガシー仮IDが残っていれば掃除する(別ユーザーの仮IDは残す)
            val legacyId = "$LEGACY_ID_PREFIX$displayName"
            if (legacyId != accountId) accounts.remove(legacyId)
            saveAccounts(accounts)
            try {
                localStorage[KEY_ACTIVE_ACCOUNT] = accountId
            } catch (e: Throwable) {
                // 保存失敗時は今回の表示だけ有効化する
            }

            emitActiveOrUnauthenticated()

            window.location.href = "/"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // ktor-client-jsがブラウザのfetch()失敗時にExceptionをすり抜けて
            // 投げる場合があるためThrowableで受ける(既存の描画クラッシュ対策と同様)。
            _authState.value = AuthState.Error(e.message ?: "Authentication failed")
        }
    }

    /** 既存アカウントへの切替え。存在しないIDなら何もしない。 */
    fun switchAccount(id: String) {
        try {
            val accounts = loadAccounts()
            if (!accounts.containsKey(id)) return
            localStorage[KEY_ACTIVE_ACCOUNT] = id
            // 期限切れ(またはまもなく期限切れ)のアカウントへ切り替えるときは、
            // 期限切れトークンでAPIを叩いて401にする前に先にリフレッシュする(issue #184)。
            emitActiveAccountOrRefresh {
                scheduleAutoRefresh()
            }
        } catch (e: Throwable) {
            // 切替え失敗時は現状維持し、描画クラッシュさせない
        }
    }

    /** ローカルからの削除のみ(Keycloak側のセッションには触れない)。
     * 有効アカウントを消した場合は残りの先頭を有効化し、空になれば未認証にする。 */
    fun removeAccount(id: String) {
        try {
            val accounts = loadAccounts()
            if (!accounts.containsKey(id)) return
            accounts.remove(id)
            if (accounts.isEmpty()) {
                localStorage.removeItem(KEY_ACCOUNTS)
                localStorage.removeItem(KEY_ACTIVE_ACCOUNT)
                refreshJob?.cancel()
                _authState.value = AuthState.Unauthenticated
                return
            }
            saveAccounts(accounts)
            val currentActiveId = try {
                localStorage[KEY_ACTIVE_ACCOUNT]
            } catch (e: Throwable) {
                null
            }
            if (currentActiveId == id || currentActiveId == null) {
                try {
                    localStorage[KEY_ACTIVE_ACCOUNT] = accounts.keys.first()
                } catch (e: Throwable) {
                    // 保存失敗時は今回の表示だけ有効化する
                }
            }
            // 残った先頭アカウントのトークンが期限切れなら、401を避けるため先に
            // リフレッシュしてから認証済みを流す(issue #184)。
            emitActiveAccountOrRefresh {
                scheduleAutoRefresh()
            }
        } catch (e: Throwable) {
            // 削除失敗時は現状維持し、描画クラッシュさせない
        }
    }

    /** 全アカウントの削除+Keycloakリダイレクト(従来logoutと同等)。 */
    fun logoutAll() {
        val activeId = try {
            localStorage[KEY_ACTIVE_ACCOUNT]
        } catch (e: Throwable) {
            null
        }
        val idToken = try {
            if (activeId != null) loadAccounts()[activeId]?.idToken else null
        } catch (e: Throwable) {
            null
        }
        refreshJob?.cancel()
        // 新キー群
        try {
            localStorage.removeItem(KEY_ACCOUNTS)
            localStorage.removeItem(KEY_ACTIVE_ACCOUNT)
            // 旧単一キー群(移行漏れ対策)
            localStorage.removeItem(KEY_ACCESS_TOKEN)
            localStorage.removeItem(KEY_ID_TOKEN)
            localStorage.removeItem(KEY_REFRESH_TOKEN)
            localStorage.removeItem(KEY_EXPIRES_AT)
            localStorage.removeItem(KEY_USERNAME)
        } catch (e: Throwable) {
            // 無視
        }
        _authState.value = AuthState.Unauthenticated

        val params = URLSearchParams()
        params.set("client_id", KeycloakConfig.clientId)
        params.set("post_logout_redirect_uri", window.location.origin)
        if (idToken != null) params.set("id_token_hint", idToken)

        window.location.href = "${KeycloakConfig.logoutUrl()}?$params"
    }

    /** 従来の単一ログアウト。現在は全アカウント削除(logoutAll)と同義。 */
    fun logout() = logoutAll()

    override fun close() {
        refreshJob?.cancel()
        scope.cancel()
        httpClient.close()
    }
}
