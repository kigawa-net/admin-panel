package net.kigawa.admin.server

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * k8s-system#220: AlertmanagerのWatchdogアラート(評価パイプラインが生きている限り常時
 * firingするダミーアラート)をdead man's switchとして利用するための受信口。Alertmanager
 * からのWebhookをここで受け止め、最終受信時刻をDBに記録する。一定時間pingが来なければ
 * 「Alertmanager/Prometheus自体が落ちている」とみなせる。
 *
 * MariaDB未設定(isDatabaseConfigured=false)の環境では機能全体を無効化する(他の機能と
 * 同じ方針)。WATCHDOG_WEBHOOK_SECRET未設定の環境でも同様に無効化する。
 */
private val watchdogWebhookSecret = System.getenv("WATCHDOG_WEBHOOK_SECRET")

internal val isWatchdogConfigured: Boolean get() = !watchdogWebhookSecret.isNullOrBlank()

/** Watchdogのrepeat_intervalを5分に設定している前提で、その3倍の余裕を見る。 */
internal const val WATCHDOG_STALE_AFTER_SECONDS = 15L * 60

@Serializable
data class WatchdogStatusDto(
    val configured: Boolean,
    /** 最終ping受信からの経過秒数。フロントエンド側で日時パース用の依存を増やさないため、
     * ISO文字列ではなくサーバー側で計算済みの経過秒数を返す。 */
    val secondsSinceLastPing: Long?,
    val staleAfterSeconds: Long,
    val isStale: Boolean,
)

internal fun isValidWatchdogSecret(provided: String?): Boolean {
    val expected = watchdogWebhookSecret
    if (expected.isNullOrBlank() || provided.isNullOrBlank()) return false
    return provided == expected
}

internal suspend fun recordWatchdogPing() {
    if (!isDatabaseConfigured) return
    withDbConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO watchdog_ping (id, last_ping_at) VALUES (1, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE last_ping_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { it.executeUpdate() }
    }
}

private suspend fun fetchLastWatchdogPing(): Instant? {
    if (!isDatabaseConfigured) return null
    return withDbConnection { conn ->
        conn.prepareStatement("SELECT last_ping_at FROM watchdog_ping WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getTimestamp("last_ping_at").toInstant() else null
            }
        }
    }
}

internal suspend fun fetchWatchdogStatus(): WatchdogStatusDto {
    if (!isWatchdogConfigured || !isDatabaseConfigured) {
        return WatchdogStatusDto(
            configured = false,
            secondsSinceLastPing = null,
            staleAfterSeconds = WATCHDOG_STALE_AFTER_SECONDS,
            isStale = false
        )
    }
    val last = fetchLastWatchdogPing()
    val secondsSinceLastPing = last?.let { Instant.now().epochSecond - it.epochSecond }
    val isStale = secondsSinceLastPing == null || secondsSinceLastPing > WATCHDOG_STALE_AFTER_SECONDS
    return WatchdogStatusDto(
        configured = true,
        secondsSinceLastPing = secondsSinceLastPing,
        staleAfterSeconds = WATCHDOG_STALE_AFTER_SECONDS,
        isStale = isStale
    )
}
