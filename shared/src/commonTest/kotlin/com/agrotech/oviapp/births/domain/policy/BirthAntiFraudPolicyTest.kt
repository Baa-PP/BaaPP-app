package com.agrotech.oviapp.births.domain.policy

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BirthAntiFraudPolicyTest {

    @Test
    fun testBoundaryDay0AndDay30AllowedForOwnerAndWorker() {
        val today = "2026-11-22"

        // Day 0 (born today)
        for (role in listOf(UserRole.OWNER, UserRole.WORKER_BRANCH)) {
            val resDay0 = BirthAntiFraudPolicy.evaluate(
                birthDateIso = "2026-11-22",
                todayDateIso = today,
                role = role
            )
            val allowed0 = assertIs<BirthAntiFraudPolicy.ValidationResult.Allowed>(resDay0)
            assertEquals(0L, allowed0.daysElapsed)

            // Day 30 (2026-10-23 -> 2026-11-22 = 30 days)
            val resDay30 = BirthAntiFraudPolicy.evaluate(
                birthDateIso = "2026-10-23",
                todayDateIso = today,
                role = role
            )
            val allowed30 = assertIs<BirthAntiFraudPolicy.ValidationResult.Allowed>(resDay30)
            assertEquals(30L, allowed30.daysElapsed)
        }
    }

    @Test
    fun testBoundaryDay31BlockedForOwnerAndWorkerOffline() {
        val today = "2026-11-22"
        // 2026-10-22 -> 2026-11-22 = 31 days
        for (role in listOf(UserRole.OWNER, UserRole.WORKER_BRANCH)) {
            val res = BirthAntiFraudPolicy.evaluate(
                birthDateIso = "2026-10-22",
                todayDateIso = today,
                role = role
            )
            val denied = assertIs<BirthAntiFraudPolicy.ValidationResult.Denied>(res)
            assertEquals(BirthAntiFraudPolicy.DenialReason.WINDOW_CLOSED, denied.reason)
            assertEquals(31L, denied.daysElapsed)
            assertEquals(BirthAntiFraudPolicy.WINDOW_CLOSED_MESSAGE, denied.message)
        }
    }

    @Test
    fun testFutureBirthDateRejectedForAllRoles() {
        val today = "2026-11-22"
        for (role in UserRole.entries) {
            val res = BirthAntiFraudPolicy.evaluate(
                birthDateIso = "2026-11-23",
                todayDateIso = today,
                role = role
            )
            val denied = assertIs<BirthAntiFraudPolicy.ValidationResult.Denied>(res)
            assertEquals(BirthAntiFraudPolicy.DenialReason.FUTURE_DATE, denied.reason)
            assertEquals(-1L, denied.daysElapsed)
            assertEquals(BirthAntiFraudPolicy.FUTURE_DATE_MESSAGE, denied.message)
        }
    }

    @Test
    fun testInvalidCalendarDateRejected() {
        val res = BirthAntiFraudPolicy.evaluate(
            birthDateIso = "2025-02-29",
            todayDateIso = "2025-03-01",
            role = UserRole.OWNER
        )
        val denied = assertIs<BirthAntiFraudPolicy.ValidationResult.Denied>(res)
        assertEquals(BirthAntiFraudPolicy.DenialReason.INVALID_DATE, denied.reason)
    }

    @Test
    fun testSessionPermissionsAndVetTechnicianAuditedBypass() {
        val today = "2026-11-22"
        val vetSession = AuthSession(
            accessToken = "tok",
            username = "vet1",
            role = "VET_TECHNICIAN",
            sessionId = "s-vet"
        )
        val vetRes = BirthAntiFraudPolicy.evaluate(
            birthDateIso = "2026-09-01", // > 30 days
            todayDateIso = today,
            session = vetSession
        )
        assertIs<BirthAntiFraudPolicy.ValidationResult.Allowed>(vetRes)

        val workerWithoutCapture = AuthSession(
            accessToken = "tok",
            username = "worker-ro",
            role = "WORKER_BRANCH",
            permissions = mapOf("lectura" to true, "captura" to false),
            sessionId = "s-worker"
        )
        val deniedWorker = BirthAntiFraudPolicy.evaluate(
            birthDateIso = today,
            todayDateIso = today,
            session = workerWithoutCapture
        )
        val denied = assertIs<BirthAntiFraudPolicy.ValidationResult.Denied>(deniedWorker)
        assertEquals(BirthAntiFraudPolicy.DenialReason.ROLE_NOT_PERMITTED, denied.reason)
    }
}
