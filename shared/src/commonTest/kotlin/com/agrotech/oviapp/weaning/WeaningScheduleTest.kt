package com.agrotech.oviapp.weaning

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeaningScheduleTest {
    private val birth = LocalDate(2026, 9, 8)

    @Test
    fun theScheduledDateIsBirthPlusTheRanchTarget() {
        assertEquals(LocalDate(2026, 11, 22), WeaningSchedule.scheduledDate(birth, WeaningSchedule.DEFAULT_TARGET_DAYS))
        assertEquals(LocalDate(2026, 11, 7), WeaningSchedule.scheduledDate(birth, 60))
        assertEquals(LocalDate(2026, 12, 7), WeaningSchedule.scheduledDate(birth, 90))
    }

    @Test
    fun aLambThatIsExactly75DaysOldIsPending() {
        val info = WeaningSchedule.forLamb(birth, weaningDate = null, today = LocalDate(2026, 11, 22))

        assertEquals(WeaningStatus.PENDING, info.status)
        assertEquals(75, info.ageDays)
        assertEquals(0, info.daysUntilWeaning)
    }

    @Test
    fun anOverdueLambStaysPendingWithNegativeDays() {
        val info = WeaningSchedule.forLamb(birth, weaningDate = null, today = LocalDate(2026, 11, 25))

        assertEquals(WeaningStatus.PENDING, info.status)
        assertEquals(-3, info.daysUntilWeaning)
    }

    @Test
    fun lambsInsideTheWindowAreUpcomingAndTheRestAreLater() {
        assertEquals(WeaningStatus.UPCOMING, WeaningSchedule.forLamb(birth, null, LocalDate(2026, 11, 21)).status)
        assertEquals(WeaningStatus.UPCOMING, WeaningSchedule.forLamb(birth, null, LocalDate(2026, 11, 8)).status)
        assertEquals(WeaningStatus.LATER, WeaningSchedule.forLamb(birth, null, LocalDate(2026, 11, 7)).status)
    }

    @Test
    fun aWeanedLambIsNoLongerPending() {
        val info = WeaningSchedule.forLamb(birth, weaningDate = LocalDate(2026, 11, 20), today = LocalDate(2026, 11, 25))

        assertEquals(WeaningStatus.WEANED, info.status)
        assertEquals(LocalDate(2026, 11, 22), info.scheduledDate)
    }

    @Test
    fun usesTheRanchTargetInsteadOfTheDefault() {
        val info = WeaningSchedule.forLamb(birth, null, today = LocalDate(2026, 11, 7), targetDays = 60)

        assertEquals(WeaningStatus.PENDING, info.status)
    }

    @Test
    fun theTargetMustBeOneOfTheRanchOptions() {
        assertEquals(setOf(60, 75, 90), WeaningSchedule.ALLOWED_TARGET_DAYS)
        assertFailsWith<IllegalArgumentException> { WeaningSchedule.scheduledDate(birth, 0) }
        assertFailsWith<IllegalArgumentException> { WeaningSchedule.forLamb(birth, null, birth, upcomingWindowDays = -1) }
    }

    @Test
    fun theOldestBirthThatStillFallsInsideTheWindow() {
        // Hoy 22/11, meta 75, ventana 14 → entra todo nacido hasta el 22/11 + 14 - 75 = 22/09.
        assertEquals(LocalDate(2026, 9, 22), WeaningSchedule.latestBirthDateToList(LocalDate(2026, 11, 22), 75, 14))
    }
}
