package net.kigawa.admin.server

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 組織管理APIが「設定不足」で使えない場合に、その原因が画面のメッセージまで届くこと
 * (kigawa-net/admin-panel#129)を確認する。
 */
class OrganizationApiAvailabilityTest {
    @Test
    fun `explains which secret to populate when credentials are missing`() {
        val reason = organizationApiUnavailableReason(clientId = null, clientSecret = null)
        assertNotNull(reason)
        assertTrue(
            reason.contains("KEYCLOAK_ORG_API_CLIENT_ID"),
            "未設定の旨が分かるように環境変数名を含むこと: $reason"
        )
        assertTrue(
            reason.contains("admin-panel-org-service-keycloak"),
            "どこを直すべきか分かるようにSecret名を含むこと: $reason"
        )
    }

    @Test
    fun `treats a blank secret as missing`() {
        assertNotNull(organizationApiUnavailableReason(clientId = "admin-panel-org-service", clientSecret = "   "))
    }

    @Test
    fun `reports no reason when both credentials are present`() {
        assertNull(
            organizationApiUnavailableReason(
                clientId = "admin-panel-org-service",
                clientSecret = "s3cret"
            )
        )
    }
}
