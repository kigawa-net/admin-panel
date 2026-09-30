package net.kigawa.admin.auth

import java.util.prefs.Preferences

private const val KEY_USERNAME = "username"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_IS_ADMIN = "is_admin"

/**
 * Persists to the OS-native preferences backing store (Windows registry / macOS plist / a
 * dotfile under the user's home on Linux) so the session survives closing and reopening the
 * desktop app, matching the web client's localStorage-based "remember me" behavior.
 */
class PreferencesTokenStorage : TokenStorage {
    private val prefs = Preferences.userNodeForPackage(PreferencesTokenStorage::class.java)

    override fun save(session: PersistedSession) {
        prefs.put(KEY_USERNAME, session.username)
        prefs.put(KEY_ACCESS_TOKEN, session.accessToken)
        if (session.refreshToken != null) {
            prefs.put(KEY_REFRESH_TOKEN, session.refreshToken)
        } else {
            prefs.remove(KEY_REFRESH_TOKEN)
        }
        prefs.putLong(KEY_EXPIRES_AT, session.expiresAt)
        // 管理者フラグが無い旧保存形式は false(非管理者・安全側)で読まれる
        prefs.putBoolean(KEY_IS_ADMIN, session.isAdmin)
        prefs.flush()
    }

    override fun load(): PersistedSession? {
        val username = prefs.get(KEY_USERNAME, null) ?: return null
        val accessToken = prefs.get(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.get(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val isAdmin = prefs.getBoolean(KEY_IS_ADMIN, false)
        return PersistedSession(username, accessToken, refreshToken, expiresAt, isAdmin)
    }

    override fun clear() {
        prefs.clear()
        prefs.flush()
    }
}