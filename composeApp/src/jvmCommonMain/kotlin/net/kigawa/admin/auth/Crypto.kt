package net.kigawa.admin.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()
private const val ALLOWED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

actual fun secureRandomString(length: Int): String {
    val sb = StringBuilder(length)
    repeat(length) {
        sb.append(ALLOWED_CHARS[secureRandom.nextInt(ALLOWED_CHARS.length)])
    }
    return sb.toString()
}

actual fun sha256Base64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
