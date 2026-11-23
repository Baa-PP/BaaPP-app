package com.agrotech.oviapp.births.domain.policy

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.RanchAccessPolicy
import com.agrotech.oviapp.auth.domain.UserRole

/**
 * Local offline-first 30-day anti-fraud policy for birth registrations (RF-AND-04 / APP-11).
 * Aligned with backend rule BE-007: 0 <= (today - birthDate) <= 30 days for producer roles.
 */
object BirthAntiFraudPolicy {

    const val MAX_WINDOW_DAYS: Long = 30L
    const val WINDOW_CLOSED_MESSAGE: String =
        "Ventana de 30 días cerrada. Solicite el alta auditada a la Asociación/Técnico con su evidencia fotográfica."
    const val FUTURE_DATE_MESSAGE: String = "La fecha de parto no puede ser futura."
    const val INVALID_DATE_MESSAGE: String = "La fecha de parto debe tener formato válido YYYY-MM-DD."
    const val READ_ONLY_ROLE_MESSAGE: String =
        "Tu cuenta no tiene permiso para registrar nacimientos."

    enum class DenialReason {
        INVALID_DATE,
        FUTURE_DATE,
        WINDOW_CLOSED,
        ROLE_NOT_PERMITTED
    }

    sealed class ValidationResult {
        data class Allowed(val daysElapsed: Long) : ValidationResult()
        data class Denied(
            val message: String,
            val reason: DenialReason,
            val daysElapsed: Long? = null
        ) : ValidationResult()
    }

    fun evaluate(
        birthDateIso: String,
        todayDateIso: String,
        session: AuthSession
    ): ValidationResult {
        val capabilities = RanchAccessPolicy.resolveCapabilities(session)
        if (!capabilities.canCapture) {
            return ValidationResult.Denied(
                message = READ_ONLY_ROLE_MESSAGE,
                reason = DenialReason.ROLE_NOT_PERMITTED
            )
        }
        return evaluate(birthDateIso = birthDateIso, todayDateIso = todayDateIso, role = capabilities.role)
    }

    fun evaluate(
        birthDateIso: String,
        todayDateIso: String,
        role: UserRole
    ): ValidationResult {
        val birthDay = parseIsoToEpochDay(birthDateIso)
            ?: return ValidationResult.Denied(
                message = INVALID_DATE_MESSAGE,
                reason = DenialReason.INVALID_DATE
            )
        val todayDay = parseIsoToEpochDay(todayDateIso)
            ?: return ValidationResult.Denied(
                message = INVALID_DATE_MESSAGE,
                reason = DenialReason.INVALID_DATE
            )

        val daysElapsed = todayDay - birthDay
        if (daysElapsed < 0L) {
            return ValidationResult.Denied(
                message = FUTURE_DATE_MESSAGE,
                reason = DenialReason.FUTURE_DATE,
                daysElapsed = daysElapsed
            )
        }

        return when (role) {
            UserRole.OWNER, UserRole.WORKER_BRANCH -> {
                if (daysElapsed <= MAX_WINDOW_DAYS) {
                    ValidationResult.Allowed(daysElapsed = daysElapsed)
                } else {
                    ValidationResult.Denied(
                        message = WINDOW_CLOSED_MESSAGE,
                        reason = DenialReason.WINDOW_CLOSED,
                        daysElapsed = daysElapsed
                    )
                }
            }
            UserRole.VET_TECHNICIAN -> ValidationResult.Allowed(daysElapsed = daysElapsed)
            UserRole.ASSOCIATION_SUPERUSER -> ValidationResult.Denied(
                message = READ_ONLY_ROLE_MESSAGE,
                reason = DenialReason.ROLE_NOT_PERMITTED,
                daysElapsed = daysElapsed
            )
        }
    }

    private fun parseIsoToEpochDay(raw: String): Long? {
        val parts = raw.trim().split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year !in 1900..9999 || month !in 1..12) return null
        if (day !in 1..daysInMonth(year, month)) return null
        return toEpochDay(year, month, day)
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

    private fun toEpochDay(year: Int, month: Int, day: Int): Long {
        val y = year.toLong() - if (month <= 2) 1L else 0L
        val era = (if (y >= 0) y else y - 399L) / 400L
        val yoe = y - era * 400L
        val m = month.toLong()
        val doy = (153L * (m + if (m > 2) -3L else 9L) + 2L) / 5L + day.toLong() - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146097L + doe - 719468L
    }
}
