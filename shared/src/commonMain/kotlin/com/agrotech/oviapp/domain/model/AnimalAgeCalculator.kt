package com.agrotech.oviapp.domain.model

data class AnimalAge(
    val months: Int,
    val days: Int,
    val totalDays: Long
) {
    fun formatSpanish(): String {
        val monthLabel = if (months == 1) "mes" else "meses"
        val dayLabel = if (days == 1) "día" else "días"
        return "$months $monthLabel, $days $dayLabel"
    }
}

/**
 * Pure Kotlin calendar age calculator in months and days from ISO-8601 dates (YYYY-MM-DD).
 */
object AnimalAgeCalculator {

    private data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
        override fun compareTo(other: CalendarDate): Int {
            if (year != other.year) return year.compareTo(other.year)
            if (month != other.month) return month.compareTo(other.month)
            return day.compareTo(other.day)
        }
    }

    fun calculateAge(birthDateIso: String, referenceDateIso: String): AnimalAge? {
        val birth = parseIsoDate(birthDateIso) ?: return null
        val ref = parseIsoDate(referenceDateIso) ?: return null
        if (ref < birth) return null

        var months = (ref.year - birth.year) * 12 + (ref.month - birth.month)
        var days = ref.day - birth.day

        if (days < 0) {
            months -= 1
            val prevMonth = if (ref.month == 1) 12 else ref.month - 1
            val prevYear = if (ref.month == 1) ref.year - 1 else ref.year
            val prevMonthDays = daysInMonth(prevYear, prevMonth)
            val clampedBirthDay = minOf(birth.day, prevMonthDays)
            days = ref.day + (prevMonthDays - clampedBirthDay)
        }

        val totalDays = toEpochDay(ref) - toEpochDay(birth)
        return AnimalAge(months = months, days = days, totalDays = totalDays)
    }

    private fun parseIsoDate(raw: String): CalendarDate? {
        val parts = raw.trim().split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 1900 || month !in 1..12) return null
        if (day !in 1..daysInMonth(year, month)) return null
        return CalendarDate(year, month, day)
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

    // Howard Hinnant's civil-from-days inverse algorithm for exact day differences
    private fun toEpochDay(date: CalendarDate): Long {
        val y = date.year.toLong() - if (date.month <= 2) 1L else 0L
        val era = (if (y >= 0) y else y - 399L) / 400L
        val yoe = y - era * 400L
        val m = date.month.toLong()
        val doy = (153L * (m + if (m > 2) -3L else 9L) + 2L) / 5L + date.day.toLong() - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146097L + doe - 719468L
    }
}
