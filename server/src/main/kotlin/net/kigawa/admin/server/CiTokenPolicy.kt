package net.kigawa.admin.server

data class CiTokenPolicyEntry(
    val allowedOwner: String,
    val allowedRepositories: Set<String>,
    val allowedPermissions: Map<String, String>
)

/**
 * Per-caller-repository allowlist for the CI-facing installation-token broker
 * (`POST /api/github-app/ci-token`). The caller repository comes from the verified GitHub
 * Actions OIDC token's `repository` claim (see [GithubActionsOidc]), never from client input,
 * so this map is the actual authorization boundary — not just documentation.
 *
 * To onboard a new CI caller, add an entry here.
 */
val ciTokenPolicy: Map<String, CiTokenPolicyEntry> = mapOf(
    "OneServerMC/RpgCore" to CiTokenPolicyEntry(
        allowedOwner = "OneServerMC",
        allowedRepositories = setOf("infra"),
        allowedPermissions = mapOf("contents" to "write")
    )
)

/**
 * Checks a requested token scope against the calling repository's policy entry.
 * Returns null when the request is fully within policy, or an error message describing
 * what's disallowed otherwise.
 */
fun checkCiTokenRequest(
    callerRepository: String,
    requestedOwner: String,
    requestedRepositories: List<String>?,
    requestedPermissions: Map<String, String>?
): String? {
    val policy = ciTokenPolicy[callerRepository]
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
