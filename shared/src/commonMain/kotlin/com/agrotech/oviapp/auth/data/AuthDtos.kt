package com.agrotech.oviapp.auth.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class UserDto(
    val id: String? = null,
    val username: String? = null,
    val role: String? = null,
    val ranchId: String? = null,
    val ranch_id: String? = null,
    val permissions: Map<String, Boolean>? = null,
    val branch_permissions: Map<String, Boolean>? = null
)

@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val access_token: String? = null,
    val token: String? = null,
    val jwt: String? = null,
    val refreshToken: String? = null,
    val refresh_token: String? = null,
    val role: String? = null,
    val ranchId: String? = null,
    val ranch_id: String? = null,
    val permissions: Map<String, Boolean>? = null,
    val branch_permissions: Map<String, Boolean>? = null,
    val user: UserDto? = null,
    val data: LoginResponse? = null
) {
    fun resolveAccessToken(): String? =
        data?.resolveAccessToken() ?: accessToken ?: access_token ?: token ?: jwt

    fun resolveRefreshToken(): String? =
        data?.resolveRefreshToken() ?: refreshToken ?: refresh_token

    fun resolveRole(): String =
        (data?.user?.role ?: user?.role ?: data?.role ?: role ?: "OWNER")
            .uppercase()
            .removePrefix("ROLE_")

    fun resolveRanchId(): String? =
        data?.user?.ranchId ?: user?.ranchId ?: data?.user?.ranch_id ?: user?.ranch_id ?: data?.ranchId ?: ranchId ?: data?.ranch_id ?: ranch_id

    fun resolvePermissions(): Map<String, Boolean> =
        data?.user?.branch_permissions ?: user?.branch_permissions
            ?: data?.user?.permissions ?: user?.permissions
            ?: data?.branch_permissions ?: branch_permissions
            ?: data?.permissions ?: permissions
            ?: emptyMap()

    fun resolveUsername(fallback: String): String =
        data?.user?.username ?: user?.username ?: fallback
}
