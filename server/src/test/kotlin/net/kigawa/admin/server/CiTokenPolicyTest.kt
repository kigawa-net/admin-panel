package net.kigawa.admin.server

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CiTokenPolicyTest {
    @Test
    fun `allows a request fully within policy`() {
        val error = checkCiTokenRequest(
            callerRepository = "OneServerMC/RpgCore",
            requestedOwner = "OneServerMC",
            requestedRepositories = listOf("infra"),
            requestedPermissions = mapOf("contents" to "write")
        )
        assertNull(error)
    }

    @Test
    fun `rejects an unknown caller repository`() {
        val error = checkCiTokenRequest(
            callerRepository = "someone/unrelated",
            requestedOwner = "OneServerMC",
            requestedRepositories = listOf("infra"),
            requestedPermissions = mapOf("contents" to "write")
        )
        assertTrue(error?.contains("not authorized") == true)
    }

    @Test
    fun `rejects a repository outside the allowlist`() {
        val error = checkCiTokenRequest(
            callerRepository = "OneServerMC/RpgCore",
            requestedOwner = "OneServerMC",
            requestedRepositories = listOf("infra", "some-other-repo"),
            requestedPermissions = mapOf("contents" to "write")
        )
        assertTrue(error?.contains("some-other-repo") == true)
    }

    @Test
    fun `rejects a permission outside the allowlist`() {
        val error = checkCiTokenRequest(
            callerRepository = "OneServerMC/RpgCore",
            requestedOwner = "OneServerMC",
            requestedRepositories = listOf("infra"),
            requestedPermissions = mapOf("contents" to "write", "administration" to "write")
        )
        assertTrue(error != null)
    }

    @Test
    fun `rejects an owner mismatch`() {
        val error = checkCiTokenRequest(
            callerRepository = "OneServerMC/RpgCore",
            requestedOwner = "kigawa-net",
            requestedRepositories = listOf("infra"),
            requestedPermissions = mapOf("contents" to "write")
        )
        assertTrue(error?.contains("owner") == true)
    }
}
