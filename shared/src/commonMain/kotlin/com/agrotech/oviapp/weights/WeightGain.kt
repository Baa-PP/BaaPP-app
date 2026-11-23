package com.agrotech.oviapp.weights

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

data class WeightRecord(
    val id: String,
    val milestone: WeightMilestone,
    val date: LocalDate,
    val weightHundredths: Long,
    val scrotalHundredths: Long?,
)

/**
 * Un renglón del historial con su ganancia contra el pesaje anterior. La GDP se da en g/día
 * (entero) y en kg/día con 4 decimales (diezmilésimas), igual que `ZootechnicalCalculator`.
 */
data class WeightGainEntry(
    val record: WeightRecord,
    val ageDays: Int,
    val previousWeightHundredths: Long?,
    val daysSincePrevious: Int?,
    val gainHundredths: Long?,
    val dailyGainGrams: Long?,
    val dailyGainKgTenThousandths: Long?,
)

data class WeightHistory(
    val entries: List<WeightGainEntry>,
    val latestWeightHundredths: Long?,
    val overallDailyGainGrams: Long?,
    val overallDailyGainKgTenThousandths: Long?,
)

/** Cálculo local (sin conexión) equivalente a `GetAnimalWeightsUseCase` de BE-009. */
object WeightGain {
    fun ageDays(birthDate: LocalDate, date: LocalDate): Int = birthDate.daysUntil(date)

    /** kg en centésimas → g/día: (Δ/100 kg × 1000) / días = Δ × 10 / días. */
    fun dailyGainGrams(current: Long, previous: Long, days: Int): Long? =
        if (days <= 0) null else Decimals.divideHalfUp((current - previous) * 10, days.toLong())

    /** kg en centésimas → kg/día en diezmilésimas: Δ × 100 / días. */
    fun dailyGainKgTenThousandths(current: Long, previous: Long, days: Int): Long? =
        if (days <= 0) null else Decimals.divideHalfUp((current - previous) * 100, days.toLong())

    fun history(birthDate: LocalDate, records: List<WeightRecord>): WeightHistory {
        // Mismo orden que el backend: fecha y luego id, para que pesajes del mismo día sean estables.
        val sorted = records.sortedWith(compareBy<WeightRecord>({ it.date }, { it.id }))
        val entries = sorted.mapIndexed { index, record ->
            val previous = sorted.getOrNull(index - 1)
            val days = previous?.let { it.date.daysUntil(record.date) }
            WeightGainEntry(
                record = record,
                ageDays = ageDays(birthDate, record.date),
                previousWeightHundredths = previous?.weightHundredths,
                daysSincePrevious = days,
                gainHundredths = previous?.let { record.weightHundredths - it.weightHundredths },
                dailyGainGrams = previous?.let { dailyGainGrams(record.weightHundredths, it.weightHundredths, days!!) },
                dailyGainKgTenThousandths = previous?.let {
                    dailyGainKgTenThousandths(record.weightHundredths, it.weightHundredths, days!!)
                },
            )
        }

        val first = sorted.firstOrNull()
        val last = sorted.lastOrNull()
        val totalDays = if (first != null && last != null) first.date.daysUntil(last.date) else 0
        return WeightHistory(
            entries = entries,
            latestWeightHundredths = last?.weightHundredths,
            overallDailyGainGrams = if (first != null && last != null) {
                dailyGainGrams(last.weightHundredths, first.weightHundredths, totalDays)
            } else {
                null
            },
            overallDailyGainKgTenThousandths = if (first != null && last != null) {
                dailyGainKgTenThousandths(last.weightHundredths, first.weightHundredths, totalDays)
            } else {
                null
            },
        )
    }
}
