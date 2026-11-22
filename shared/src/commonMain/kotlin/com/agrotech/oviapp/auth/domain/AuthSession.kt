package com.agrotech.oviapp.auth.domain

import kotlinx.serialization.Serializable

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val username: String,
    val role: String,
    val ranchId: String? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val sessionId: String = ""
) {
    val normalizedRole: String get() = role.uppercase().removePrefix("ROLE_").trim()

    val userRole: UserRole get() = UserRole.fromRaw(role)

    val isOwner: Boolean get() = userRole == UserRole.OWNER
    val isWorkerBranch: Boolean get() = userRole == UserRole.WORKER_BRANCH
    val isVetTechnician: Boolean get() = userRole == UserRole.VET_TECHNICIAN
    val isAssociationSuperuser: Boolean get() = userRole == UserRole.ASSOCIATION_SUPERUSER

    fun hasPermission(permissionKey: String): Boolean {
        if (isOwner || isAssociationSuperuser || isVetTechnician) return true
        return permissions[permissionKey] == true
    }

    fun hasPermission(permission: RanchPermission): Boolean {
        if (isOwner || isAssociationSuperuser || isVetTechnician) return true
        val explicit = permissions[permission.jsonKey]
        if (explicit != null) return explicit
        return permission.legacyAliases.any { permissions[it] == true }
    }

    fun uiCapabilities(): UiAccessCapabilities = RanchAccessPolicy.resolveCapabilities(this)
}
