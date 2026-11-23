package com.agrotech.oviapp.domain.model

internal expect fun currentEpochMillis(): Long
internal expect fun nextRandomBytes(size: Int): ByteArray

/**
 * Client-side RFC 9562 UUIDv7 generator for offline-first primary keys.
 */
object UuidV7 {
    private val UUID_V7_REGEX =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun generate(): String = generate(
        timestampMillis = currentEpochMillis(),
        randomBytes = nextRandomBytes(10)
    )

    internal fun generate(timestampMillis: Long, randomBytes: ByteArray): String {
        require(randomBytes.size >= 10) { "At least 10 random bytes are required for UUIDv7" }

        val bytes = ByteArray(16)

        // 48-bit big-endian Unix epoch milliseconds timestamp
        bytes[0] = ((timestampMillis ushr 40) and 0xFF).toByte()
        bytes[1] = ((timestampMillis ushr 32) and 0xFF).toByte()
        bytes[2] = ((timestampMillis ushr 24) and 0xFF).toByte()
        bytes[3] = ((timestampMillis ushr 16) and 0xFF).toByte()
        bytes[4] = ((timestampMillis ushr 8) and 0xFF).toByte()
        bytes[5] = (timestampMillis and 0xFF).toByte()

        // 16 bits: 4-bit version (0111 = 7) + 12-bit rand_a
        bytes[6] = ((randomBytes[0].toInt() and 0x0F) or 0x70).toByte()
        bytes[7] = randomBytes[1]

        // 64 bits: 2-bit variant (10) + 62-bit rand_b
        bytes[8] = ((randomBytes[2].toInt() and 0x3F) or 0x80).toByte()
        for (i in 9 until 16) {
            bytes[i] = randomBytes[i - 6]
        }

        return formatUuid(bytes)
    }

    fun isValid(uuid: String): Boolean = UUID_V7_REGEX.matches(uuid.lowercase())

    private fun formatUuid(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                sb.append('-')
            }
            val v = bytes[i].toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
