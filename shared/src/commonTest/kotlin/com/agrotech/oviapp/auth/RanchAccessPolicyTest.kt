package com.agrotech.oviapp.auth

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.RanchPermission
import com.agrotech.oviapp.auth.domain.UserRole
import com.agrotech.oviapp.births.domain.model.BirthImmutabilityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RanchAccessPolicyTest {

    @Test
    fun testOwnerCanCaptureAndEditGeneralButCannotEditSavedBirths() {
        val session = AuthSession(
            accessToken = "tok",
            username = "dueno_1",
            role = "OWNER",
            ranchId = "ranch-1"
        )

        val caps = session.uiCapabilities()
        assertEquals(UserRole.OWNER, caps.role)
        assertTrue(caps.canReadInventory)
        assertTrue(caps.canCapture)
        assertTrue(caps.canEditGeneralRecords)
        assertTrue(caps.canExport)
        assertFalse(caps.canEditSavedBirths, "OWNER must not see edit controls on saved births")
        assertTrue(caps.showBirthImmutabilityNotice)
        assertFalse(caps.isReadOnlyMode)
        assertFalse(caps.isMultiRanchScope)
    }

    @Test
    fun testWorkerWithoutCaptureDoesNotSeeCaptureActions() {
        val session = AuthSession(
            accessToken = "tok",
            username = "trabajador_lectura",
            role = "WORKER_BRANCH",
            ranchId = "ranch-1",
            permissions = mapOf(
                "lectura" to true,
                "captura" to false,
                "edicion" to false,
                "exportacion" to false
            )
        )

        val caps = session.uiCapabilities()
        assertEquals(UserRole.WORKER_BRANCH, caps.role)
        assertTrue(caps.canReadInventory)
        assertFalse(caps.canCapture, "Worker without captura must not see capture actions")
        assertFalse(caps.canEditGeneralRecords)
        assertFalse(caps.canEditSavedBirths)
        assertFalse(caps.canExport)
        assertTrue(caps.isReadOnlyMode)
        assertTrue(caps.showBirthImmutabilityNotice)
    }

    @Test
    fun testWorkerWithFullPermissionsStillCannotEditSavedBirths() {
        val session = AuthSession(
            accessToken = "tok",
            username = "encargado_full",
            role = "WORKER_BRANCH",
            ranchId = "ranch-1",
            permissions = mapOf(
                "lectura" to true,
                "captura" to true,
                "edicion" to true,
                "exportacion" to true
            )
        )

        val caps = session.uiCapabilities()
        assertTrue(caps.canReadInventory)
        assertTrue(caps.canCapture)
        assertTrue(caps.canEditGeneralRecords)
        assertTrue(caps.canExport)
        assertFalse(
            caps.canEditSavedBirths,
            "WORKER_BRANCH must never see edit controls on saved births even with edicion=true"
        )
        assertTrue(caps.showBirthImmutabilityNotice)
        assertFalse(caps.isReadOnlyMode)
    }

    @Test
    fun testWorkerWithEmptyPermissionsIsDeniedByDefault() {
        val session = AuthSession(
            accessToken = "tok",
            username = "trabajador_sin_permisos",
            role = "WORKER_BRANCH",
            ranchId = "ranch-1",
            permissions = emptyMap()
        )

        val caps = session.uiCapabilities()
        assertFalse(caps.canReadInventory)
        assertFalse(caps.canCapture)
        assertFalse(caps.canEditGeneralRecords)
        assertFalse(caps.canEditSavedBirths)
        assertFalse(caps.canExport)
        assertTrue(caps.isReadOnlyMode)
    }

    @Test
    fun testVetTechnicianHasTechnicalCaptureAndAuditedBirthEdit() {
        val session = AuthSession(
            accessToken = "tok",
            username = "vet_1",
            role = "ROLE_VET_TECHNICIAN"
        )

        val caps = session.uiCapabilities()
        assertEquals(UserRole.VET_TECHNICIAN, caps.role)
        assertTrue(caps.canReadInventory)
        assertTrue(caps.canCapture)
        assertTrue(caps.canEditGeneralRecords)
        assertTrue(caps.canEditSavedBirths)
        assertTrue(caps.canExport)
        assertTrue(caps.isMultiRanchScope)
        assertFalse(caps.isReadOnlyMode)
        assertFalse(caps.showBirthImmutabilityNotice)
    }

    @Test
    fun testAssociationSuperuserIsMultiRanchReadOnlyOnMobile() {
        val session = AuthSession(
            accessToken = "tok",
            username = "asociacion_1",
            role = "ASSOCIATION_SUPERUSER"
        )

        val caps = session.uiCapabilities()
        assertEquals(UserRole.ASSOCIATION_SUPERUSER, caps.role)
        assertTrue(caps.canReadInventory)
        assertFalse(caps.canCapture)
        assertFalse(caps.canEditGeneralRecords)
        assertFalse(caps.canEditSavedBirths)
        assertTrue(caps.canExport)
        assertTrue(caps.isReadOnlyMode)
        assertTrue(caps.isMultiRanchScope)
    }

    @Test
    fun testRoleNormalizationAndPermissionExplicitOverride() {
        assertEquals(UserRole.OWNER, UserRole.fromRaw(" role_owner "))
        assertEquals(UserRole.WORKER_BRANCH, UserRole.fromRaw(null))
        assertEquals(UserRole.WORKER_BRANCH, UserRole.fromRaw("ADMIN_UNKNOWN"))

        // Explicit jsonKey=false overrides legacy alias
        val session = AuthSession(
            accessToken = "tok",
            username = "worker",
            role = "WORKER_BRANCH",
            permissions = mapOf("lectura" to false, "read" to true, "capture" to true)
        )
        assertFalse(session.hasPermission(RanchPermission.LECTURA))
        assertTrue(session.hasPermission(RanchPermission.CAPTURA))
    }

    @Test
    fun testBirthImmutabilityNoticeIndicatesAuditedPhotoCorrection() {
        val notice = BirthImmutabilityPolicy.AUDITED_CORRECTION_NOTICE
        assertTrue(notice.contains("inmutables", ignoreCase = true))
        assertTrue(notice.contains("foto", ignoreCase = true))
        assertTrue(notice.contains("Asociación/Técnico", ignoreCase = true))
    }
}
