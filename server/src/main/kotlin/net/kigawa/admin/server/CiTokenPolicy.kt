package net.kigawa.admin.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CiTokenPolicyEntry(
    val allowedOwner: String,
    val allowedRepositories: Set<String>,
    val allowedPermissions: Map<String, String>
)

@Serializable
data class CiTokenPolicyEntryDto(
    val callerRepository: String,
    val allowedOwner: String,
    val allowedRepositories: List<String>,
    val allowedPermissions: Map<String, String>
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Per-caller-repository allowlist for the CI-facing installation-token broker
 * (`POST /api/github-app/ci-token`). The caller repository comes from the verified GitHub
 * Actions OIDC token's `repository` claim (see [GithubActionsOidc]), never from client input,
 * so this table is the actual authorization boundary — not just documentation.
 *
 * admin-panel#64: previously a hardcoded Map in this file, requiring a code change + PR to
 * onboard each new CI caller. Now backed by admin-panel's own MariaDB and managed from the
 * admin UI (GET/PUT/DELETE /api/github-app/ci-policy) instead.
 */

// DBが空(初回起動時)の場合にのみ、コードにハードコードされていた唯一の既存エントリを
// 初期データとして投入する。これが無いと、このリポジトリのDB移行そのもので既存のCI呼び出し
// (OneServerMC/RpgCore)が突然認可エラーになってしまう。
private val defaultPolicySeed = CiTokenPolicyEntryDto(
    callerRepository = "OneServerMC/RpgCore",
    allowedOwner = "OneServerMC",
    allowedRepositories = listOf("infra"),
    allowedPermissions = mapOf("contents" to "write")
)

internal suspend fun seedCiTokenPolicyIfEmpty() {
    if (listCiTokenPolicies().isEmpty()) {
        upsertCiTokenPolicy(defaultPolicySeed)
    }
}

suspend fun listCiTokenPolicies(): List<CiTokenPolicyEntryDto> = withDbConnection { conn ->
    conn.prepareStatement(
        "SELECT caller_repository, allowed_owner, allowed_repositories, allowed_permissions " +
            "FROM ci_token_policy ORDER BY caller_repository"
    ).use { stmt ->
        stmt.executeQuery().use { rs ->
            val results = mutableListOf<CiTokenPolicyEntryDto>()
            while (rs.next()) {
                results += CiTokenPolicyEntryDto(
                    callerRepository = rs.getString("caller_repository"),
                    allowedOwner = rs.getString("allowed_owner"),
                    allowedRepositories = json.decodeFromString(rs.getString("allowed_repositories")),
                    allowedPermissions = json.decodeFromString(rs.getString("allowed_permissions"))
                )
            }
            results
        }
    }
}

private suspend fun getCiTokenPolicy(callerRepository: String): CiTokenPolicyEntry? = withDbConnection { conn ->
    conn.prepareStatement(
        "SELECT allowed_owner, allowed_repositories, allowed_permissions FROM ci_token_policy WHERE caller_repository = ?"
    ).use { stmt ->
        stmt.setString(1, callerRepository)
        stmt.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                CiTokenPolicyEntry(
                    allowedOwner = rs.getString("allowed_owner"),
                    allowedRepositories = json.decodeFromString<List<String>>(rs.getString("allowed_repositories")).toSet(),
                    allowedPermissions = json.decodeFromString(rs.getString("allowed_permissions"))
                )
            }
        }
    }
}

suspend fun upsertCiTokenPolicy(entry: CiTokenPolicyEntryDto) {
    withDbConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO ci_token_policy (caller_repository, allowed_owner, allowed_repositories, allowed_permissions)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE allowed_owner = VALUES(allowed_owner),
                allowed_repositories = VALUES(allowed_repositories),
                allowed_permissions = VALUES(allowed_permissions)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, entry.callerRepository)
            stmt.setString(2, entry.allowedOwner)
            stmt.setString(3, json.encodeToString(entry.allowedRepositories))
            stmt.setString(4, json.encodeToString(entry.allowedPermissions))
            stmt.executeUpdate()
        }
    }
}

/** Returns true if an entry existed and was removed. */
suspend fun deleteCiTokenPolicy(callerRepository: String): Boolean = withDbConnection { conn ->
    conn.prepareStatement("DELETE FROM ci_token_policy WHERE caller_repository = ?").use { stmt ->
        stmt.setString(1, callerRepository)
        stmt.executeUpdate() > 0
    }
}

/**
 * Checks a requested token scope against the calling repository's policy entry.
 * Returns null when the request is fully within policy, or an error message describing
 * what's disallowed otherwise.
 */
suspend fun checkCiTokenRequest(
    callerRepository: String,
    requestedOwner: String,
    requestedRepositories: List<String>?,
    requestedPermissions: Map<String, String>?
): String? {
    val policy = getCiTokenPolicy(callerRepository)
        ?: return "repository '$callerRepository' is not authorized to request CI tokens"

    if (requestedOwner != policy.allowedOwner) {
        return "owner '$requestedOwner' is not allowed for '$callerRepository' (expected '${policy.allowedOwner}')"
    }

    if (requestedRepositories.isNullOrEmpty()) {
        return "repositories must be specified explicitly"
    }
    val disallowedRepos = requestedRepositories.filterNot { it in policy.allowedRepositories }
    if (disallowedRepos.isNotEmpty()) {
        return "repositories not allowed for '$callerRepository': $disallowedRepos"
    }

    if (requestedPermissions.isNullOrEmpty()) {
        return "permissions must be specified explicitly"
    }
    val disallowedPerms = requestedPermissions.filterNot { (key, value) -> policy.allowedPermissions[key] == value }
    if (disallowedPerms.isNotEmpty()) {
        return "permissions not allowed for '$callerRepository': $disallowedPerms"
    }

    return null
}
