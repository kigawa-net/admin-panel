package net.kigawa.admin.auth

import android.content.Context

private const val PREFS_NAME = "keycloak_auth"
private const val KEY_USERNAME = "username"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_IS_ADMIN = "is_admin"

class SharedPreferencesTokenStorage(context: Context) : TokenStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(session: PersistedSession) {
        prefs.edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            // 管理者フラグが無い旧保存形式は false(非管理者・安全側)で読まれる
            .putBoolean(KEY_IS_ADMIN, session.isAdmin)
            .apply()
    }

    override fun load(): PersistedSession? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val isAdmin = prefs.getBoolean(KEY_IS_ADMIN, false)
        return PersistedSession(username, accessToken, refreshToken, expiresAt, isAdmin)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}