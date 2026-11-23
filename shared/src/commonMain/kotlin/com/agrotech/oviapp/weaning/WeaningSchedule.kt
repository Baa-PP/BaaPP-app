package com.agrotech.oviapp.weaning

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

enum class WeaningStatus {
    /** Ya cumplió (o pasó) la fecha programada y no se ha destetado. */
    PENDING,

    /** Le toca dentro de la ventana de próximos días. */
    UPCOMING,

    /** Todavía falta más que la ventana. */
    LATER,
    WEANED,
}

/** Lo que se muestra en la cría: cuándo toca destetarla y cuánto falta (negativo = días de atraso). */
data class WeaningInfo(
    val scheduledDate: LocalDate,
    val ageDays: Int,
    val daysUntilWeaning: Int,
    val status: WeaningStatus,
)

/**
 * Fecha programada de destete = fecha de nacimiento + `dias_meta_destete` del rancho, el mismo
 * criterio del `WeaningAlertScheduler` del backend (ACTIVE y sin fecha de destete).
 */
object WeaningSchedule {
    const val DEFAULT_TARGET_DAYS = 75
    const val DEFAULT_UPCOMING_WINDOW_DAYS = 14

    /** Valores que acepta el backend para `dias_meta_destete` (RanchPolicy). */
    val ALLOWED_TARGET_DAYS: Set<Int> = setOf(60, 75, 90)

    fun scheduledDate(birthDate: LocalDate, targetDays: Int = DEFAULT_TARGET_DAYS): LocalDate {
        require(targetDays > 0) { "targetDays must be positive" }
        return birthDate.plus(DatePeriod(days = targetDays))
    }

    fun forLamb(
        birthDate: LocalDate,
        weaningDate: LocalDate?,
        today: LocalDate,
        targetDays: Int = DEFAULT_TARGET_DAYS,
        upcomingWindowDays: Int = DEFAULT_UPCOMING_WINDOW_DAYS,
    ): WeaningInfo {
        require(upcomingWindowDays >= 0) { "upcomingWindowDays must not be negative" }
        val scheduled = scheduledDate(birthDate, targetDays)
        val daysUntil = today.daysUntil(scheduled)
        val status = when {
            weaningDate != null -> WeaningStatus.WEANED
            daysUntil <= 0 -> WeaningStatus.PENDING
            daysUntil <= upcomingWindowDays -> WeaningStatus.UPCOMING
            else -> WeaningStatus.LATER
        }
        return WeaningInfo(scheduled, birthDate.daysUntil(today), daysUntil, status)
    }

    /** Las crías nacidas después de esta fecha todavía no entran ni a próximos; sirve para filtrar en SQL. */
    fun latestBirthDateToList(today: LocalDate, targetDays: Int, upcomingWindowDays: Int): LocalDate =
        today.plus(DatePeriod(days = upcomingWindowDays)).minus(DatePeriod(days = targetDays))
}
