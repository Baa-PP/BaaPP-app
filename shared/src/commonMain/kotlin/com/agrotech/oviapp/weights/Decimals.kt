package com.agrotech.oviapp.weights

import kotlin.math.abs

/** Aritmética decimal mínima en enteros escalados (KMP no tiene BigDecimal en commonMain). */
object Decimals {
    /** [exact] es false si el texto traía más de dos decimales distintos de cero. */
    data class Parsed(val hundredths: Long, val exact: Boolean)

    private val NUMBER = Regex("""^-?\d{1,9}([.,]\d+)?$""")

    fun parseHundredths(text: String): Parsed? {
        val clean = text.trim()
        if (!NUMBER.matches(clean)) return null
        val negative = clean.startsWith("-")
        val parts = clean.removePrefix("-").split('.', ',')
        val fraction = parts.getOrElse(1) { "" }
        val kept = fraction.take(2).padEnd(2, '0').toLong()
        val exact = fraction.drop(2).all { it == '0' }
        val value = parts[0].toLong() * 100 + kept
        return Parsed(if (negative) -value else value, exact)
    }

    /** División con redondeo HALF_UP (la mitad se aleja de cero), igual que `RoundingMode.HALF_UP`. */
    fun divideHalfUp(numerator: Long, denominator: Long): Long {
        require(denominator > 0) { "denominator must be positive" }
        val quotient = abs(numerator) / denominator
        val remainder = abs(numerator) % denominator
        val rounded = if (remainder * 2 >= denominator) quotient + 1 else quotient
        return if (numerator < 0) -rounded else rounded
    }

    /** 2500 con scale 4 → "0.2500"; -50 con scale 2 → "-0.50". */
    fun format(value: Long, scale: Int): String {
        var factor = 1L
        repeat(scale) { factor *= 10 }
        val sign = if (value < 0) "-" else ""
        val whole = abs(value) / factor
        if (scale == 0) return "$sign$whole"
        val fraction = (abs(value) % factor).toString().padStart(scale, '0')
        return "$sign$whole.$fraction"
    }
}
