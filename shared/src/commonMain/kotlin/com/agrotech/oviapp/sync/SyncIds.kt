package com.agrotech.oviapp.sync

import kotlin.random.Random

/** Hora actual del dispositivo en milisegundos desde 1970 (UTC). */
expect fun currentTimeMillis(): Long

/**
 * UUID versión 7 (RFC 9562): los primeros 48 bits son el instante en milisegundos, así que los ids
 * generados en el celular sin conexión se ordenan por fecha de creación y no chocan entre dispositivos.
 */
object UuidV7 {
    fun generate(epochMillis: Long = currentTimeMillis(), random: Random = Random.Default): String {
        val randomBits = random.nextBytes(10)
        val bytes = ByteArray(16)
        for (i in 0 until 6) {
            bytes[i] = (epochMillis ushr (8 * (5 - i))).toByte()
        }
        randomBits.copyInto(bytes, destinationOffset = 6)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte() // versión 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variante RFC 4122

        val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/**
 * ISO-8601 en UTC con milisegundos siempre presentes ("2026-11-22T15:30:05.000Z").
 * El ancho fijo permite ordenar por texto en SQLite, que es como la cola lee en orden cronológico.
 */
object IsoTimestamp {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun format(epochMillis: Long): String {
        val days = epochMillis.floorDiv(MILLIS_PER_DAY)
        val millisOfDay = epochMillis.mod(MILLIS_PER_DAY)
        val (year, month, day) = civilFromDays(days)
        val hour = millisOfDay / 3_600_000
        val minute = millisOfDay / 60_000 % 60
        val second = millisOfDay / 1_000 % 60
        val millis = millisOfDay % 1_000
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T" +
            "${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}.${pad(millis, 3)}Z"
    }

    private fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')

    /** Algoritmo "days from civil" inverso de Howard Hinnant: días desde 1970-01-01 a año, mes y día. */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
        val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }
}
