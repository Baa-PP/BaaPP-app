package com.agrotech.oviapp.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimalDomainTests {

    // --- SINIIGA Validation Tests ---

    @Test
    fun testSiniigaOptionalNullOrBlankIsValid() {
        val nullRes = SiniigaValidator.normalizeAndValidate(null)
        assertIs<SiniigaValidationResult.Valid>(nullRes)
        assertNull(nullRes.normalizedChip)

        val blankRes = SiniigaValidator.normalizeAndValidate("   ")
        assertIs<SiniigaValidationResult.Valid>(blankRes)
        assertNull(blankRes.normalizedChip)
    }

    @Test
    fun testSiniigaValid10To15DigitsWith484Prefix() {
        // 10 digits (minimum)
        val minRes = SiniigaValidator.normalizeAndValidate(" 4840000001 ")
        assertIs<SiniigaValidationResult.Valid>(minRes)
        assertEquals("4840000001", minRes.normalizedChip)

        // 15 digits (maximum)
        val maxRes = SiniigaValidator.normalizeAndValidate("484123456789012")
        assertIs<SiniigaValidationResult.Valid>(maxRes)
        assertEquals("484123456789012", maxRes.normalizedChip)
    }

    @Test
    fun testSiniigaRejectsInvalidPrefixLengthOrNonDigits() {
        assertFalse(SiniigaValidator.isValid("4851234567"))        // Wrong country prefix
        assertFalse(SiniigaValidator.isValid("484123456"))         // 9 digits (too short)
        assertFalse(SiniigaValidator.isValid("4841234567890123"))  // 16 digits (too long)
        assertFalse(SiniigaValidator.isValid("484123456A"))        // Non-digit character
        assertIs<SiniigaValidationResult.Invalid>(SiniigaValidator.normalizeAndValidate("12345"))
    }

    // --- Client-side UUIDv7 Tests ---

    @Test
    fun testUuidV7ClientGenerationFormatAndUniqueness() {
        val generated = (1..100).map { UuidV7.generate() }
        generated.forEach { id ->
            assertTrue(UuidV7.isValid(id), "Expected valid UUIDv7 format, got: $id")
            assertEquals('7', id[14], "Version nibble must be 7")
            assertTrue(id[19] in setOf('8', '9', 'a', 'b'), "Variant nibble must be RFC 9562 (8/9/a/b)")
        }
        assertEquals(100, generated.toSet().size, "Generated UUIDv7 values must be unique")
    }

    @Test
    fun testUuidV7EncodesTimestampAndSortsChronologically() {
        val fixedRand = ByteArray(10) { 0x11 }
        val earlier = UuidV7.generate(timestampMillis = 1_700_000_000_000L, randomBytes = fixedRand)
        val later = UuidV7.generate(timestampMillis = 1_700_000_005_000L, randomBytes = fixedRand)

        assertTrue(UuidV7.isValid(earlier))
        assertTrue(UuidV7.isValid(later))
        assertNotEquals(earlier, later)
        assertTrue(earlier < later, "UUIDv7 must sort lexicographically by timestamp")
    }

    // --- Age Calculation Tests ---

    @Test
    fun testAgeCalculationSameDayAndExactMonths() {
        val sameDay = AnimalAgeCalculator.calculateAge("2026-05-10", "2026-05-10")
        assertEquals(AnimalAge(months = 0, days = 0, totalDays = 0), sameDay)
        assertEquals("0 meses, 0 días", sameDay?.formatSpanish())

        val exact = AnimalAgeCalculator.calculateAge("2026-01-15", "2026-04-20")
        assertEquals(AnimalAge(months = 3, days = 5, totalDays = 95), exact)
        assertEquals("3 meses, 5 días", exact?.formatSpanish())

        val oneMonthOneDay = AnimalAgeCalculator.calculateAge("2026-03-10", "2026-04-11")
        assertEquals("1 mes, 1 día", oneMonthOneDay?.formatSpanish())
    }

    @Test
    fun testAgeCalculationMonthEndAndLeapYearBoundaries() {
        // Leap day to non-leap Feb 28
        val leapToFeb28 = AnimalAgeCalculator.calculateAge("2024-02-29", "2025-02-28")
        assertEquals(AnimalAge(months = 11, days = 30, totalDays = 365), leapToFeb28)

        // Jan 31 to Mar 1 in non-leap year (28 days in Feb)
        val jan31ToMar1 = AnimalAgeCalculator.calculateAge("2026-01-31", "2026-03-01")
        assertEquals(AnimalAge(months = 1, days = 1, totalDays = 29), jan31ToMar1)

        // 75-day weaning milestone check
        val weaningAge = AnimalAgeCalculator.calculateAge("2026-01-01", "2026-03-17")
        assertEquals(75L, weaningAge?.totalDays)
        assertEquals(2, weaningAge?.months)
        assertEquals(16, weaningAge?.days)
    }

    @Test
    fun testAgeCalculationRejectsInvalidOrFutureBirthDates() {
        assertNull(AnimalAgeCalculator.calculateAge("2026-05-10", "2026-05-09"))
        assertNull(AnimalAgeCalculator.calculateAge("2026-02-30", "2026-03-10"))
        assertNull(AnimalAgeCalculator.calculateAge("invalid", "2026-03-10"))
    }

    // --- Catalogs & Orthogonal Model Tests ---

    @Test
    fun testAnimalCatalogsAndOrthogonalDimensions() {
        assertTrue(AnimalCatalogs.BREEDS.containsAll(listOf("Dorper", "Pelifolk", "Pelibuey", "Katahdin")))
        assertEquals(
            setOf(EntryReason.BIRTH, EntryReason.PURCHASE, EntryReason.REENTRY, EntryReason.TRANSFER, EntryReason.OTHER),
            AnimalCatalogs.ENTRY_REASONS.toSet()
        )
        assertEquals(5, AnimalCatalogs.PARTURITION_TYPES.size)
        assertEquals(3, AnimalCatalogs.CONCEPTION_METHODS.size)

        val animal = Animal(
            id = UuidV7.generate(),
            ranchId = UuidV7.generate(),
            folioInterno = "AIR-2253-H",
            siniigaChip = "484000012345678",
            registroUno = "268309MM-RP",
            adn = "ADN-991",
            breed = "Pelifolk",
            color = "Blanco",
            sex = Sex.FEMALE,
            birthDateIso = "2026-01-10",
            entryReason = EntryReason.BIRTH.name,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.LACTATING,
            status = AnimalStatus.ACTIVE
        )
        assertTrue(UuidV7.isValid(animal.id))
        assertTrue(SiniigaValidator.isValid(animal.siniigaChip))
    }
}
