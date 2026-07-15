package net.kigawa.admin

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sun.net.httpserver.HttpServer
import net.kigawa.admin.auth.KeycloakAuthProvider
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder

private fun parseQueryParams(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val parts = pair.split("=", limit = 2)
        val key = URLDecoder.decode(parts[0], "UTF-8")
        val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        key to value
    }.toMap()
}

/**
 * Redirect URIs for a native desktop app must point back to a port on the same machine, so we
 * spin up a throwaway loopback HTTP server just to catch Keycloak's Authorization Code redirect
 * before the actual token exchange happens over a normal HTTP client call.
 */
private fun createDesktopAuthProvider(): KeycloakAuthProvider {
    lateinit var provider: KeycloakAuthProvider

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val redirectUri = "http://127.0.0.1:${server.address.port}/callback"

    server.createContext("/callback") { exchange ->
        val params = parseQueryParams(exchange.requestURI.query)
        val body = "<html><body>ログインが完了しました。このウィンドウを閉じてアプリに戻ってください。</body></html>"
            .toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        provider.handleAuthorizationResponse(params["code"], params["state"], params["error"])
    }
    server.start()

    provider = KeycloakAuthProvider(
        redirectUri = redirectUri,
        launchAuthorizationUrl = { url -> Desktop.getDesktop().browse(URI(url)) }
    )
    return provider
}

fun main() {
    val authProvider = createDesktopAuthProvider()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Admin Panel"
        ) {
            App(authProvider = authProvider)
        }
    }
}
