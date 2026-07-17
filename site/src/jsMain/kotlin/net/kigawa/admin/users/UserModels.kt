package net.kigawa.admin.users

import kotlinx.serialization.Serializable

@Serializable
data class KeycloakUser(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class KeycloakUserList(val users: List<KeycloakUser>)

@Serializable
data class CreateUserRequest(
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val temporaryPassword: String
)

@Serializable
data class ResetPasswordRequest(
    val newPassword: String,
    val temporary: Boolean = true
)

@Serializable
data class UserActionResult(val success: Boolean, val message: String)
