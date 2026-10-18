package net.kigawa.admin.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ci-token の観測ログに出た文字列(整形済みメッセージ)を集める。テスト後に appender を外す。 */
internal fun captureCiTokenLogs(block: () -> Unit): List<String> {
    val logger = ciTokenObserveLogger as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.map { it.formattedMessage }
}

class CiTokenObservationTest {
    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun claims(
        repository: String? = "kigawa-net/kivra",
        observed: ObservedActionsClaims = ObservedActionsClaims(),
    ) = ActionsClaims(
        issuer = "https://token.actions.githubusercontent.com",
        repository = repository,
        expiresAt = 0,
        observed = observed,
    )

    private val kivraCd = ObserveConstraintDto(
        repositoryId = "1347995166",
        workflowRefs = listOf("kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/main"),
        refs = listOf("refs/heads/main"),
        events = listOf("push", "workflow_dispatch"),
    )

    // ---- クレームの取り出し ----

    @Test
    fun `extracts string claims`() {
        val observed = ObservedActionsClaims.from(payload("""{"ref":"refs/heads/main","event_name":"push"}"""))
        assertEquals("refs/heads/main", observed.ref)
        assertEquals("push", observed.eventName)
        assertNull(observed.workflowRef)
    }

    @Test
    fun `missing claims are null`() {
        assertEquals(ObservedActionsClaims.NONE, ObservedActionsClaims.from(payload("{}")))
    }

    @Test
    fun `non-string claims are null`() {
        val observed = ObservedActionsClaims.from(
            payload("""{"repository_id":1347995166,"ref":null,"event_name":true,"workflow_ref":["a"],"sha":{"x":1}}""")
        )
        assertNull(observed.repositoryId)
        assertNull(observed.ref)
        assertNull(observed.eventName)
        assertNull(observed.workflowRef)
        assertNull(observed.sha)
    }

    @Test
    fun `empty claims are null`() {
        assertNull(ObservedActionsClaims.from(payload("""{"ref":""}""")).ref)
    }

    @Test
    fun `claims at the length limit are kept and longer ones are null`() {
        val atLimit = "a".repeat(OBSERVED_CLAIM_MAX_LENGTH)
        val overLimit = "a".repeat(OBSERVED_CLAIM_MAX_LENGTH + 1)
        assertEquals(atLimit, ObservedActionsClaims.from(payload("""{"ref":"$atLimit"}""")).ref)
        assertNull(ObservedActionsClaims.from(payload("""{"ref":"$overLimit"}""")).ref)
    }

    @Test
    fun `claims containing control characters are null`() {
        val observed = ObservedActionsClaims.from(payload("""{"ref":"refs/heads/a\nb","event_name":"push\u0000"}"""))
        assertNull(observed.ref)
        assertNull(observed.eventName)
    }

    // ---- ログ値のサニタイズ ----

    @Test
    fun `log values drop control characters and break key-value syntax characters`() {
        assertEquals("-", logValue(null))
        assertEquals("-", logValue("\u0000\n"))
        assertEquals("a_b_c_d", logValue("a b=c,d"))
        assertEquals("ab", logValue("a\rb"))
        val long = "x".repeat(300)
        val logged = logValue(long)
        assertTrue(logged.length < 300)
        assertTrue(logged.endsWith("..."))
    }

    @Test
    fun `log lists are capped`() {
        val many = (1..25).map { "r$it" }
        val logged = logList(many)
        assertTrue(logged.startsWith("[r1,r2,"))
        assertTrue(logged.endsWith(",...]"))
        assertEquals("-", logList(null))
    }

    // ---- 想定の制約の解析と不一致の検出 ----

    @Test
    fun `no constraint configuration means nothing is parsed`() {
        assertTrue(parseObserveConstraints(null).isEmpty())
        assertTrue(parseObserveConstraints("   ").isEmpty())
    }

    @Test
    fun `invalid constraint JSON is ignored instead of failing`() {
        assertTrue(parseObserveConstraints("{not json").isEmpty())
        assertTrue(parseObserveConstraints("""{"kigawa-net/kivra":{"events":"push"}}""").isEmpty())
    }

    @Test
    fun `constraint JSON is parsed per caller repository`() {
        val parsed = parseObserveConstraints(
            """{"kigawa-net/kivra":{"repositoryId":"1347995166","events":["push"],"unknown":1}}"""
        )
        assertEquals(listOf("push"), parsed["kigawa-net/kivra"]?.events)
        assertEquals("1347995166", parsed["kigawa-net/kivra"]?.repositoryId)
    }

    @Test
    fun `matching claims produce no mismatch`() {
        val observed = ObservedActionsClaims(
            repositoryId = "1347995166",
            ref = "refs/heads/main",
            eventName = "workflow_dispatch",
            workflowRef = "kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/main",
        )
        assertTrue(constraintMismatches(observed, kivraCd).isEmpty())
    }

    @Test
    fun `a differing workflow ref is reported`() {
        val observed = ObservedActionsClaims(
            repositoryId = "1347995166",
            ref = "refs/heads/main",
            eventName = "push",
            workflowRef = "kigawa-net/kivra/.github/workflows/evil.yml@refs/heads/feature",
        )
        val mismatches = constraintMismatches(observed, kivraCd)
        assertEquals(listOf("workflow_ref"), mismatches.map { it.field })
        assertEquals("kigawa-net/kivra/.github/workflows/evil.yml@refs/heads/feature", mismatches[0].actual)
    }

    @Test
    fun `a missing claim counts as a mismatch when it is constrained`() {
        val mismatches = constraintMismatches(ObservedActionsClaims(), kivraCd)
        assertEquals(
            listOf("repository_id", "workflow_ref", "ref", "event_name"),
            mismatches.map { it.field }
        )
        assertTrue(mismatches.all { it.actual == null })
    }

    @Test
    fun `unconstrained fields are not checked`() {
        val constraint = ObserveConstraintDto(events = listOf("push"))
        assertTrue(constraintMismatches(ObservedActionsClaims(eventName = "push", ref = "x"), constraint).isEmpty())
    }

    @Test
    fun `an empty allowed list never matches`() {
        val constraint = ObserveConstraintDto(environments = emptyList())
        val mismatches = constraintMismatches(ObservedActionsClaims(environment = "dev"), constraint)
        assertEquals(listOf("environment"), mismatches.map { it.field })
    }

    // ---- 観測ログの内容 ----

    @Test
    fun `mismatch warning is logged with the observe-only marker and no other claims`() {
        val logs = captureCiTokenLogs {
            observeConstraintMismatches(
                claims(observed = ObservedActionsClaims(eventName = "pull_request", repositoryId = "1347995166")),
                constraints = mapOf("kigawa-net/kivra" to kivraCd),
            )
        }
        assertTrue(logs.any {
            it.startsWith("ci-token constraint mismatch (observe-only): repository=kigawa-net/kivra field=event_name ")
        }, logs.toString())
        assertFalse(logs.any { it.contains("field=repository_id") })
    }

    @Test
    fun `no warning when there is no configuration for the caller`() {
        val logs = captureCiTokenLogs {
            observeConstraintMismatches(
                claims(repository = "someone/else", observed = ObservedActionsClaims(eventName = "pull_request")),
                constraints = mapOf("kigawa-net/kivra" to kivraCd),
            )
            observeConstraintMismatches(claims(observed = ObservedActionsClaims(eventName = "pull_request")), constraints = emptyMap())
        }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `decision line has the expected keys and no raw claim or request values beyond the list`() {
        val logs = captureCiTokenLogs {
            observeCiTokenDecision(
                allowed = false,
                reason = "policy_denied",
                claims = claims(observed = ObservedActionsClaims(
                    repositoryId = "1347995166",
                    ref = "refs/heads/feature",
                    eventName = "pull_request",
                    workflowRef = "kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/feature",
                    environment = "dev",
                )),
                owner = "kigawa-net",
                repositories = listOf("kigawa-net-k8s"),
                permissions = mapOf("contents" to "write", "pull_requests" to "write"),
            )
        }
        assertEquals(1, logs.size)
        val line = logs.single()
        assertTrue(line.startsWith("event=ci.token.observe decision=deny reason=policy_denied "), line)
        assertTrue(line.contains("repository=kigawa-net/kivra "))
        assertTrue(line.contains("workflow_ref=kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/feature "))
        assertTrue(line.contains("repositories=[kigawa-net-k8s] permission_keys=[contents,pull_requests]"))
    }

    @Test
    fun `decision line for an unauthenticated caller carries no caller claims`() {
        val logs = captureCiTokenLogs {
            observeCiTokenDecision(false, "unauthenticated", null, null, null, null)
        }
        assertEquals(
            "event=ci.token.observe decision=deny reason=unauthenticated repository=- repository_id=- ref=- " +
                "event_name=- workflow_ref=- job_workflow_ref=- environment=- owner=- repositories=- permission_keys=-",
            logs.single()
        )
    }

    @Test
    fun `request-supplied values are sanitized before logging`() {
        val logs = captureCiTokenLogs {
            observeCiTokenDecision(
                allowed = false,
                reason = "policy_denied",
                claims = null,
                owner = "kigawa-net\nevent=ci.token.minted decision=allow",
                repositories = listOf("a\rb"),
                permissions = mapOf("contents\u0007" to "write"),
            )
        }
        val line = logs.single()
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("\r"))
        assertFalse(line.contains("\u0007"))
        assertTrue(line.contains("owner=kigawa-netevent_ci.token.minted_decision_allow "), line)
    }
}
