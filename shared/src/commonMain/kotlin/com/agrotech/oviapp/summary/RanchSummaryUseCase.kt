package com.agrotech.oviapp.summary

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.weaning.PendingWeaningsUseCase
import com.agrotech.oviapp.weaning.SqlDelightWeaningStore
import com.agrotech.oviapp.weaning.WeaningSchedule
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/** Tarjetas de la pantalla principal (APP-25). */
data class RanchSummary(
    val activeAnimals: Long,
    val ewes: Long,
    val pregnantEwes: Long,
    val lactatingEwes: Long,
    val emptyEwes: Long,
    val rams: Long,
    val lambs: Long,
    val lactatingLambs: Long,
    val fatteningLambs: Long,
    val replacementLambs: Long,
    val pendingWeanings: Long,
    val deathsThisMonth: Long,
) {
    companion object {
        val EMPTY = RanchSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Resumen del rancho calculado solo con la base local, así funciona sin conexión. Los conteos usan los
 * mismos filtros que el inventario (`selectEwesFilteredPaged`, `selectRamsWithMetricsPaged`,
 * `selectLambsFilteredPaged`) y los destetes pendientes salen de [PendingWeaningsUseCase] (APP-16).
 */
class RanchSummaryUseCase(
    private val database: OviAppDatabase,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val queries get() = database.oviAppDatabaseQueries
    private val weanings = PendingWeaningsUseCase(SqlDelightWeaningStore(database), today)

    fun summary(ranchId: String, weaningTargetDays: Int = WeaningSchedule.DEFAULT_TARGET_DAYS): RanchSummary {
        val counts = queries.selectRanchSummaryCounts(ranchId).executeAsOne()
        val now = today()
        val monthStart = LocalDate(now.year, now.month, 1)
        val monthEnd = monthStart.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        return RanchSummary(
            activeAnimals = counts.active_animals,
            ewes = counts.ewes ?: 0,
            pregnantEwes = counts.pregnant_ewes ?: 0,
            lactatingEwes = counts.lactating_ewes ?: 0,
            emptyEwes = counts.empty_ewes ?: 0,
            rams = counts.rams ?: 0,
            lambs = counts.lambs ?: 0,
            lactatingLambs = counts.lactating_lambs ?: 0,
            fatteningLambs = counts.fattening_lambs ?: 0,
            replacementLambs = counts.replacement_lambs ?: 0,
            pendingWeanings = weanings.list(ranchId, weaningTargetDays).pending.size.toLong(),
            deathsThisMonth = queries.countDeathsBetween(ranchId, monthStart.toString(), monthEnd.toString()).executeAsOne(),
        )
    }
}
