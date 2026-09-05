package net.kigawa.admin.auth

import android.content.Context

private const val PREFS_NAME = "keycloak_auth"
private const val KEY_REALM = "realm"
private const val KEY_USERNAME = "username"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"

class SharedPreferencesTokenStorage(context: Context) : TokenStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(session: PersistedSession) {
        prefs.edit()
            .putString(KEY_REALM, session.realm.realmName)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .apply()
    }

    override fun load(): PersistedSession? {
        val realmName = prefs.getString(KEY_REALM, null) ?: return null
        val realm = KeycloakRealm.entries.find { it.realmName == realmName } ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return PersistedSession(realm, username, accessToken, refreshToken, expiresAt)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
