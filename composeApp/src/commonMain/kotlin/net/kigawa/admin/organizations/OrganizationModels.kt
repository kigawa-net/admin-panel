package net.kigawa.admin.organizations

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationDomain(val name: String, val verified: Boolean = false)

@Serializable
data class Organization(
    val id: String,
    val name: String,
    val alias: String? = null,
    val enabled: Boolean = true,
    val description: String? = null,
    val domains: List<OrganizationDomain> = emptyList()
)

@Serializable
data class OrganizationList(val organizations: List<Organization>)

@Serializable
data class CreateOrganizationRequest(
    val name: String,
    val domain: String,
    val description: String? = null
)

@Serializable
data class OrganizationMember(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)

@Serializable
data class OrganizationMemberList(val members: List<OrganizationMember>)

@Serializable
data class AddOrganizationMemberRequest(val userId: String)

@Serializable
data class KigawaNetUserList(val users: List<OrganizationMember>)

@Serializable
data class OrganizationActionResult(val success: Boolean, val message: String)
