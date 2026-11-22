package com.agrotech.oviapp.auth.domain

import kotlinx.serialization.Serializable

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val username: String,
    val role: String,
    val ranchId: String? = null,
    val permissions: Map<String, Boolean> = emptyMap()
) {
    val normalizedRole: String get() = role.uppercase().removePrefix("ROLE_")

    val isOwner: Boolean get() = normalizedRole == "OWNER"
    val isWorkerBranch: Boolean get() = normalizedRole == "WORKER_BRANCH"
    val isVetTechnician: Boolean get() = normalizedRole == "VET_TECHNICIAN"
    val isAssociationSuperuser: Boolean get() = normalizedRole == "ASSOCIATION_SUPERUSER"

    fun hasPermission(permissionKey: String): Boolean {
        if (isOwner || isAssociationSuperuser) return true
        return permissions[permissionKey] == true
    }
}
