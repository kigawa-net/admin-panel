package net.kigawa.admin.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/*
 * issue #227 (PR A): 観測のための CI トークン発行ログ。
 *
 * ここにあるものは一切「判定」に使わない。許可・拒否は CiTokenPolicy / GithubActionsOidc の従来どおり。
 * 目的は、kivra 等の実 CI が送ってくる OIDC クレーム(workflow_ref, ref, event_name, repository_id 等)を
 * 観測し、将来の強制(PR C)で想定外の値が正規の呼び出しを壊さないことを確かめること。
 *
 * 秘密の扱い: JWT 全文・署名部・Authorization ヘッダー・GitHub App のトークンはこのファイルのどの
 * 関数にも渡らず、ログにも出ない。ログに出すのは、検証済みクレームのうち下の一覧に限ったもの。
 */

/** 観測用に取り出すクレームの値の上限(文字数)。超えた値は null(未設定)として扱う。 */
internal const val OBSERVED_CLAIM_MAX_LENGTH = 512

private const val LOG_VALUE_MAX_LENGTH = 200
private const val LOG_LIST_MAX_ITEMS = 20
internal const val CI_TOKEN_OBSERVE_CONSTRAINTS_ENV = "CI_TOKEN_OBSERVE_CONSTRAINTS"

internal val ciTokenObserveLogger = LoggerFactory.getLogger("net.kigawa.admin.server.ci-token")

/** 検証済み GitHub Actions OIDC トークンから取り出す観測用クレーム。欠落・型違い・長すぎる値は null。 */
data class ObservedActionsClaims(
    val repositoryId: String? = null,
    val ref: String? = null,
    val eventName: String? = null,
    val workflowRef: String? = null,
    val jobWorkflowRef: String? = null,
    val environment: String? = null,
    val sha: String? = null,
    val actor: String? = null,
) {
    companion object {
        val NONE = ObservedActionsClaims()

        fun from(payload: JsonObject): ObservedActionsClaims = ObservedActionsClaims(
            repositoryId = safeClaimString(payload, "repository_id"),
            ref = safeClaimString(payload, "ref"),
            eventName = safeClaimString(payload, "event_name"),
            workflowRef = safeClaimString(payload, "workflow_ref"),
            jobWorkflowRef = safeClaimString(payload, "job_workflow_ref"),
            environment = safeClaimString(payload, "environment"),
            sha = safeClaimString(payload, "sha"),
            actor = safeClaimString(payload, "actor"),
        )
    }
}

/**
 * JSON 文字列であることだけを受け付ける。数値・真偽値・null・オブジェクト・空文字列・長すぎる値・
 * 制御文字を含む値は null とする(判定には影響させず、観測上は「未設定」として扱う)。
 */
internal fun safeClaimString(payload: JsonObject, name: String): String? {
    val primitive = payload[name] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val value = primitive.content
    if (value.isEmpty() || value.length > OBSERVED_CLAIM_MAX_LENGTH) return null
    if (value.any { it.isISOControl() }) return null
    return value
}

/**
 * ログ 1 項目用のサニタイズ。制御文字は除去し、空白・`=`・`,` は `_` に置換して key=value 形式を壊さない。
 * 長さは LOG_VALUE_MAX_LENGTH に切り詰める。null は "-"。
 */
internal fun logValue(value: String?): String {
    if (value == null) return "-"
    val cleaned = buildString {
        for (c in value) {
            when {
                c.isISOControl() -> Unit
                c.isWhitespace() || c == '=' || c == ',' -> append('_')
                else -> append(c)
            }
        }
    }
    if (cleaned.isEmpty()) return "-"
    return if (cleaned.length > LOG_VALUE_MAX_LENGTH) cleaned.take(LOG_VALUE_MAX_LENGTH) + "..." else cleaned
}

internal fun logList(values: Collection<String>?): String {
    if (values == null) return "-"
    val shown = values.take(LOG_LIST_MAX_ITEMS).joinToString(",", prefix = "[", postfix = "]") { logValue(it) }
    return if (values.size > LOG_LIST_MAX_ITEMS) shown.dropLast(1) + ",...]" else shown
}

/**
 * 想定される制約(観測専用)。フィールドが null のものは「確認しない」。空配列は「許可する値が無い」として
 * 常に不一致になる。PR C(強制)でポリシーに保存する形の素案でもある(docs は README の該当節を参照)。
 */
@Serializable
internal data class ObserveConstraintDto(
    val repositoryId: String? = null,
    val workflowRefs: List<String>? = null,
    val jobWorkflowRefs: List<String>? = null,
    val refs: List<String>? = null,
    val events: List<String>? = null,
    val environments: List<String>? = null,
)

private val observeJson = Json { ignoreUnknownKeys = true }

/** 環境変数の JSON を解析する。無い・壊れている場合は空(何も観測しない)。内容はログに出さない。 */
internal fun parseObserveConstraints(raw: String?): Map<String, ObserveConstraintDto> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching { observeJson.decodeFromString<Map<String, ObserveConstraintDto>>(raw) }
        .getOrElse {
            ciTokenObserveLogger.warn("ci-token observe constraints ignored: {} is not valid JSON", CI_TOKEN_OBSERVE_CONSTRAINTS_ENV)
            emptyMap()
        }
}

/** 起動時に一度だけ環境変数から読む。 */
internal val observeConstraintsFromEnv: Map<String, ObserveConstraintDto> by lazy {
    parseObserveConstraints(System.getenv(CI_TOKEN_OBSERVE_CONSTRAINTS_ENV))
}

internal data class ConstraintMismatch(val field: String, val expected: List<String>, val actual: String?)

/** 想定の制約と観測したクレームの差。クレームが欠落している場合も不一致(actual = null)。 */
internal fun constraintMismatches(
    claims: ObservedActionsClaims,
    constraint: ObserveConstraintDto,
): List<ConstraintMismatch> {
    val checks = listOf(
        Triple("repository_id", constraint.repositoryId?.let { listOf(it) }, claims.repositoryId),
        Triple("workflow_ref", constraint.workflowRefs, claims.workflowRef),
        Triple("job_workflow_ref", constraint.jobWorkflowRefs, claims.jobWorkflowRef),
        Triple("ref", constraint.refs, claims.ref),
        Triple("event_name", constraint.events, claims.eventName),
        Triple("environment", constraint.environments, claims.environment),
    )
    return checks.mapNotNull { (field, expected, actual) ->
        when {
            expected == null -> null
            actual != null && actual in expected -> null
            else -> ConstraintMismatch(field, expected, actual)
        }
    }
}

/**
 * 観測専用: 想定の制約と合わない場合に WARN を出す。戻り値は無く、呼び出し側の判定には一切影響しない。
 * 例外は握りつぶす(ログの失敗で発行可否が変わらないようにする)。
 */
internal fun observeConstraintMismatches(
    claims: ActionsClaims,
    constraints: Map<String, ObserveConstraintDto> = observeConstraintsFromEnv,
) {
    runCatching {
        val repository = claims.repository ?: return@runCatching
        val constraint = constraints[repository] ?: return@runCatching
        constraintMismatches(claims.observed, constraint).forEach { mismatch ->
            ciTokenObserveLogger.warn(
                "ci-token constraint mismatch (observe-only): repository={} field={} expected={} actual={}",
                logValue(repository), mismatch.field, logList(mismatch.expected), logValue(mismatch.actual)
            )
        }
    }
}

/**
 * 観測専用: トークン発行の判定を 1 行で記録する。許可・拒否は呼び出し側が従来どおり決めており、
 * この関数の結果は使われない。claims が null のとき(署名不正など)は caller 系の項目を "-" にする。
 */
internal fun observeCiTokenDecision(
    allowed: Boolean,
    reason: String,
    claims: ActionsClaims?,
    owner: String?,
    repositories: List<String>?,
    permissions: Map<String, String>?,
) {
    runCatching {
        val observed = claims?.observed ?: ObservedActionsClaims.NONE
        ciTokenObserveLogger.info(
            "event=ci.token.observe decision={} reason={} repository={} repository_id={} ref={} event_name={} " +
                "workflow_ref={} job_workflow_ref={} environment={} owner={} repositories={} permission_keys={}",
            if (allowed) "allow" else "deny",
            reason,
            logValue(claims?.repository),
            logValue(observed.repositoryId),
            logValue(observed.ref),
            logValue(observed.eventName),
            logValue(observed.workflowRef),
            logValue(observed.jobWorkflowRef),
            logValue(observed.environment),
            logValue(owner),
            logList(repositories),
            logList(permissions?.keys?.sorted()),
        )
    }
}
