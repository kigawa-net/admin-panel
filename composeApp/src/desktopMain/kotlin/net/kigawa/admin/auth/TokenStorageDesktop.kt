package net.kigawa.admin.auth

import java.util.prefs.Preferences

private const val KEY_REALM = "realm"
private const val KEY_USERNAME = "username"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"

/**
 * Persists to the OS-native preferences backing store (Windows registry / macOS plist / a
 * dotfile under the user's home on Linux) so the session survives closing and reopening the
 * desktop app, matching the web client's localStorage-based "remember me" behavior.
 */
class PreferencesTokenStorage : TokenStorage {
    private val prefs = Preferences.userNodeForPackage(PreferencesTokenStorage::class.java)

    override fun save(session: PersistedSession) {
        prefs.put(KEY_REALM, session.realm.realmName)
        prefs.put(KEY_USERNAME, session.username)
        prefs.put(KEY_ACCESS_TOKEN, session.accessToken)
        if (session.refreshToken != null) {
            prefs.put(KEY_REFRESH_TOKEN, session.refreshToken)
        } else {
            prefs.remove(KEY_REFRESH_TOKEN)
        }
        prefs.putLong(KEY_EXPIRES_AT, session.expiresAt)
        prefs.flush()
    }

    override fun load(): PersistedSession? {
        val realmName = prefs.get(KEY_REALM, null) ?: return null
        val realm = KeycloakRealm.entries.find { it.realmName == realmName } ?: return null
        val username = prefs.get(KEY_USERNAME, null) ?: return null
        val accessToken = prefs.get(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.get(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return PersistedSession(realm, username, accessToken, refreshToken, expiresAt)
    }

    override fun clear() {
        prefs.clear()
        prefs.flush()
    }
}
