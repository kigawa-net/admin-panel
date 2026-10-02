package net.kigawa.admin.server

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 操作系APIの監査ログ(issue #183)。
 *
 * 以下の項目をJSON1行の構造化ログとして記録する(永続化・UIは別issueで扱う):
 * - user/sub
 * - roles
 * - operation
 * - target
 * - result
 * - timestamp
 *
 * logger名は `audit` にしてあり、通常のアプリログと分けて別ファイルへ出せる。
 */
private val auditLogger = LoggerFactory.getLogger("audit")

fun auditLog(
    principal: AdminPrincipal?,
    operation: String,
    target: String?,
    result: String,
    detail: String? = null
) {
    val line = buildJsonObject {
        put("type", "audit")
        put("sub", principal?.userId ?: "anonymous")
        putJsonArray("roles") {
            principal?.roles?.sortedBy { it.ordinal }?.forEach { add(it.name.lowercase()) }
        }
        put("operation", operation)
        if (target != null) put("target", target)
        put("result", result)
        if (detail != null) put("detail", detail)
        put("timestamp", Instant.now().toString())
    }.toString()
    auditLogger.info(line)
}
