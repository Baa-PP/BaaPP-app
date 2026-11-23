package com.agrotech.oviapp.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncIdsTest {

    @Test
    fun uuidV7HasVersion7VariantAndTimestampPrefix() {
        val epochMillis = 1_795_000_000_123L
        val id = UuidV7.generate(epochMillis, Random(42))

        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        val timestampHex = id.replace("-", "").substring(0, 12)
        assertEquals(epochMillis, timestampHex.toLong(16))
    }

    @Test
    fun uuidV7SortsByCreationTime() {
        val random = Random(7)
        val earlier = UuidV7.generate(1_795_000_000_000L, random)
        val later = UuidV7.generate(1_795_000_000_001L, random)

        assertTrue(earlier < later)
    }

    @Test
    fun uuidV7IsUniqueWithinTheSameMillisecond() {
        val random = Random(1)
        val ids = List(1_000) { UuidV7.generate(1_795_000_000_000L, random) }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun isoTimestampAlwaysHasMillisecondsSoItSortsAsText() {
        assertEquals("1970-01-01T00:00:00.000Z", IsoTimestamp.format(0L))
        assertEquals("2026-11-22T15:30:05.007Z", IsoTimestamp.format(1_795_361_405_007L))
        assertEquals("2024-02-29T23:59:59.999Z", IsoTimestamp.format(1_709_251_199_999L))

        val first = IsoTimestamp.format(1_795_361_405_000L)
        val second = IsoTimestamp.format(1_795_361_405_100L)
        assertNotEquals(first, second)
        assertTrue(first < second)
    }
}
