package net.kigawa.admin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.kigawa.admin.auth.KeycloakAuthProvider

private const val REDIRECT_SCHEME = "net.kigawa.admin"
private const val REDIRECT_HOST = "callback"
private const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"

class MainActivity : ComponentActivity() {
    private val authProvider = KeycloakAuthProvider(
        redirectUri = REDIRECT_URI,
        launchAuthorizationUrl = { url ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            App(authProvider = authProvider)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != REDIRECT_SCHEME || uri.host != REDIRECT_HOST) return
        authProvider.handleAuthorizationResponse(
            code = uri.getQueryParameter("code"),
            state = uri.getQueryParameter("state"),
            error = uri.getQueryParameter("error")
        )
    }
}
