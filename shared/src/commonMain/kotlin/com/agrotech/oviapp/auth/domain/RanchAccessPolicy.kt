package com.agrotech.oviapp.auth.domain

/**
 * Supported user roles defined in BaaPP Section 3.
 * Unknown or unrecognized roles fall back to the least-privileged [WORKER_BRANCH] role.
 */
enum class UserRole {
    OWNER,
    WORKER_BRANCH,
    VET_TECHNICIAN,
    ASSOCIATION_SUPERUSER;

    companion object {
        fun fromRaw(raw: String?): UserRole {
            val normalized = raw
                ?.trim()
                ?.uppercase()
                ?.removePrefix("ROLE_")
                ?.trim()
                ?: return WORKER_BRANCH

            return entries.firstOrNull { it.name == normalized } ?: WORKER_BRANCH
        }
    }
}

/**
 * Granular JSONB permission keys for WORKER_BRANCH accounts, matching BaaPP-backend RanchPermission.
 */
enum class RanchPermission(
    val jsonKey: String,
    val legacyAliases: Set<String> = emptySet()
) {
    LECTURA("lectura", setOf("read", "read_inventory")),
    CAPTURA("captura", setOf("capture", "create")),
    EDICION("edicion", setOf("edit", "update")),
    EXPORTACION("exportacion", setOf("export"))
}

/**
 * UI action capabilities resolved from an [AuthSession].
 * Keeps role and anti-fraud UI gating rules centralized in :shared.
 */
data class UiAccessCapabilities(
    val role: UserRole,
    val canReadInventory: Boolean,
    val canCapture: Boolean,
    val canEditGeneralRecords: Boolean,
    val canEditSavedBirths: Boolean,
    val canExport: Boolean,
    val isReadOnlyMode: Boolean,
    val isMultiRanchScope: Boolean,
    val showBirthImmutabilityNotice: Boolean
)

/**
 * Resolves UI capabilities for a given [AuthSession].
 * Server-side enforcement remains authoritative in BaaPP-backend (BE-002).
 */
object RanchAccessPolicy {
    fun resolveCapabilities(session: AuthSession): UiAccessCapabilities {
        val role = session.userRole
        return when (role) {
            UserRole.OWNER -> UiAccessCapabilities(
                role = role,
                canReadInventory = true,
                canCapture = true,
                canEditGeneralRecords = true,
                canEditSavedBirths = false,
                canExport = true,
                isReadOnlyMode = false,
                isMultiRanchScope = false,
                showBirthImmutabilityNotice = true
            )

            UserRole.WORKER_BRANCH -> {
                val canRead = session.hasPermission(RanchPermission.LECTURA)
                val canCapture = session.hasPermission(RanchPermission.CAPTURA)
                val canEdit = session.hasPermission(RanchPermission.EDICION)
                val canExport = session.hasPermission(RanchPermission.EXPORTACION)
                UiAccessCapabilities(
                    role = role,
                    canReadInventory = canRead,
                    canCapture = canCapture,
                    canEditGeneralRecords = canEdit,
                    // Saved birth records are immutable for producers regardless of JSONB edicion
                    canEditSavedBirths = false,
                    canExport = canExport,
                    isReadOnlyMode = !canCapture && !canEdit,
                    isMultiRanchScope = false,
                    showBirthImmutabilityNotice = true
                )
            }

            UserRole.VET_TECHNICIAN -> UiAccessCapabilities(
                role = role,
                canReadInventory = true,
                canCapture = true,
                canEditGeneralRecords = true,
                canEditSavedBirths = true,
                canExport = true,
                isReadOnlyMode = false,
                isMultiRanchScope = true,
                showBirthImmutabilityNotice = false
            )

            UserRole.ASSOCIATION_SUPERUSER -> UiAccessCapabilities(
                role = role,
                canReadInventory = true,
                canCapture = false,
                canEditGeneralRecords = false,
                canEditSavedBirths = false,
                canExport = true,
                isReadOnlyMode = true,
                isMultiRanchScope = true,
                showBirthImmutabilityNotice = false
            )
        }
    }
}
