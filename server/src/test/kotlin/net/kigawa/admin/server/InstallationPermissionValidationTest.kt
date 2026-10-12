package net.kigawa.admin.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallationPermissionValidationTest {
    @Test
    fun `requested write is accepted when installation grants write`() {
        assertNull(
            installationPermissionMismatch(
                requested = mapOf("contents" to "write", "pull_requests" to "write"),
                granted = mapOf("contents" to "write", "pull_requests" to "write"),
            )
        )
    }

    @Test
    fun `read grant is insufficient for pull request write`() {
        assertEquals(
            InstallationPermissionMismatch("pull_requests", "write", "read"),
            installationPermissionMismatch(
                requested = mapOf("pull_requests" to "write"),
                granted = mapOf("pull_requests" to "read"),
            )
        )
    }

    @Test
    fun `missing installation grant fails closed`() {
        assertEquals(
            InstallationPermissionMismatch("pull_requests", "write", "absent"),
            installationPermissionMismatch(
                requested = mapOf("pull_requests" to "write"),
                granted = mapOf("contents" to "write"),
            )
        )
    }

    @Test
    fun `write grant satisfies read request`() {
        assertNull(
            installationPermissionMismatch(
                requested = mapOf("pull_requests" to "read"),
                granted = mapOf("pull_requests" to "write"),
            )
        )
    }

    @Test
    fun `unknown level fails closed`() {
        assertEquals(
            InstallationPermissionMismatch("contents", "owner", "write"),
            installationPermissionMismatch(
                requested = mapOf("contents" to "owner"),
                granted = mapOf("contents" to "write"),
            )
        )
    }

    @Test
    fun `token result is independently checked`() {
        val requested = mapOf("contents" to "write", "pull_requests" to "write")
        assertNull(installationPermissionMismatch(requested, requested))
        assertEquals(
            InstallationPermissionMismatch("pull_requests", "write", "absent"),
            installationPermissionMismatch(requested, mapOf("contents" to "write")),
        )
    }
}
