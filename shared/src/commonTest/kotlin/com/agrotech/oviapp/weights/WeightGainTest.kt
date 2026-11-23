package com.agrotech.oviapp.weights

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeightGainTest {
    private val birth = LocalDate(2026, 1, 1)

    @Test
    fun ageIsCountedInDaysFromBirth() {
        assertEquals(0, WeightGain.ageDays(birth, birth))
        assertEquals(60, WeightGain.ageDays(birth, LocalDate(2026, 3, 2)))
        assertEquals(120, WeightGain.ageDays(birth, LocalDate(2026, 5, 1)))
    }

    @Test
    fun dailyGainIsMeasuredAgainstThePreviousWeighing() {
        val history = WeightGain.history(
            birth,
            listOf(
                record("w1", WeightMilestone.BIRTH, birth, 400),
                record("w2", WeightMilestone.WEANING, LocalDate(2026, 3, 2), 1900),
            ),
        )

        val first = history.entries[0]
        assertNull(first.previousWeightHundredths)
        assertNull(first.dailyGainGrams)

        val weaning = history.entries[1]
        assertEquals(60, weaning.ageDays)
        assertEquals(400, weaning.previousWeightHundredths)
        assertEquals(60, weaning.daysSincePrevious)
        assertEquals(1500, weaning.gainHundredths)
        assertEquals(250, weaning.dailyGainGrams)
        assertEquals(2500, weaning.dailyGainKgTenThousandths)
        assertEquals("0.2500", Decimals.format(weaning.dailyGainKgTenThousandths!!, scale = 4))
    }

    @Test
    fun roundsHalfUpAwayFromZeroLikeTheBackend() {
        // 10 g en 3 días = 3.33 g/día; -10 g en 20 días = -0.5 g/día → -1 (HALF_UP de BigDecimal).
        assertEquals(3, WeightGain.dailyGainGrams(current = 3501, previous = 3500, days = 3))
        assertEquals(-1, WeightGain.dailyGainGrams(current = 3499, previous = 3500, days = 20))
        assertEquals(5, WeightGain.dailyGainGrams(current = 3501, previous = 3500, days = 2))
        assertEquals(33, WeightGain.dailyGainKgTenThousandths(current = 3501, previous = 3500, days = 3))
    }

    @Test
    fun sameDayWeighingsHaveNoDailyGain() {
        val history = WeightGain.history(
            birth,
            listOf(
                record("a", WeightMilestone.EVALUATION, LocalDate(2026, 2, 1), 1000),
                record("b", WeightMilestone.EVALUATION, LocalDate(2026, 2, 1), 1010),
            ),
        )

        assertEquals(0, history.entries[1].daysSincePrevious)
        assertEquals(10, history.entries[1].gainHundredths)
        assertNull(history.entries[1].dailyGainGrams)
    }

    @Test
    fun ordersByDateThenIdBeforeComparing() {
        val history = WeightGain.history(
            birth,
            listOf(
                record("z", WeightMilestone.DAY_120, LocalDate(2026, 5, 1), 3000),
                record("b", WeightMilestone.BIRTH, birth, 400),
                record("a", WeightMilestone.BIRTH, birth, 410),
            ),
        )

        assertEquals(listOf("a", "b", "z"), history.entries.map { it.record.id })
        assertEquals(400, history.entries[2].previousWeightHundredths)
    }

    @Test
    fun overallGainGoesFromTheFirstToTheLastWeighing() {
        val history = WeightGain.history(
            birth,
            listOf(
                record("w1", WeightMilestone.BIRTH, birth, 400),
                record("w2", WeightMilestone.WEANING, LocalDate(2026, 3, 2), 1900),
                record("w3", WeightMilestone.DAY_120, LocalDate(2026, 5, 1), 3400),
            ),
        )

        assertEquals(3400, history.latestWeightHundredths)
        assertEquals(250, history.overallDailyGainGrams)
        assertEquals(2500, history.overallDailyGainKgTenThousandths)
    }

    @Test
    fun anEmptyOrSingleDayHistoryHasNoOverallGain() {
        assertNull(WeightGain.history(birth, emptyList()).overallDailyGainGrams)
        assertNull(WeightGain.history(birth, listOf(record("w1", WeightMilestone.BIRTH, birth, 400))).overallDailyGainGrams)
    }

    private fun record(id: String, milestone: WeightMilestone, date: LocalDate, hundredths: Long) =
        WeightRecord(id = id, milestone = milestone, date = date, weightHundredths = hundredths, scrotalHundredths = null)
}
