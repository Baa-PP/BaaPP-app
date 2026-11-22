package com.agrotech.oviapp.weights

import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightRulesTest {
    private val birth = LocalDate(2026, 1, 1)
    private val ram = AnimalForWeighing(id = "m1", ranchId = "r1", sex = Sex.MALE, birthDate = birth, deceased = false)
    private val ewe = ram.copy(id = "f1", sex = Sex.FEMALE)

    @Test
    fun scrotalCircumferenceIsOnlyOfferedForMales() {
        assertTrue(WeightRules.scrotalCircumferenceAvailable(Sex.MALE))
        assertFalse(WeightRules.scrotalCircumferenceAvailable(Sex.FEMALE))
    }

    @Test
    fun acceptsAMaleWeighingWithScrotalCircumference() {
        val result = WeightRules.validate(ram, input(weight = "35.25", scrotal = "28,5"))

        val valid = assertIs<WeightValidation.Valid>(result).weight
        assertEquals(3525, valid.weightHundredths)
        assertEquals(2850, valid.scrotalHundredths)
    }

    @Test
    fun rejectsScrotalCircumferenceOnAFemale() {
        assertEquals(listOf(WeightError.SCROTAL_FEMALE_NOT_ALLOWED), errors(ewe, input(scrotal = "20")))
    }

    @Test
    fun aBlankScrotalFieldMeansNotMeasured() {
        val valid = assertIs<WeightValidation.Valid>(WeightRules.validate(ewe, input(scrotal = "  "))).weight
        assertNull(valid.scrotalHundredths)
    }

    @Test
    fun weightMustBeBetweenZeroAndTheBackendMaximum() {
        assertEquals(listOf(WeightError.INVALID_WEIGHT_KG), errors(ram, input(weight = "0")))
        assertEquals(listOf(WeightError.INVALID_WEIGHT_KG), errors(ram, input(weight = "-3")))
        assertEquals(listOf(WeightError.INVALID_WEIGHT_KG), errors(ram, input(weight = "1000")))
        assertEquals(listOf(WeightError.INVALID_WEIGHT_KG), errors(ram, input(weight = "abc")))
        assertIs<WeightValidation.Valid>(WeightRules.validate(ram, input(weight = "999.99")))
    }

    @Test
    fun atMostTwoDecimals() {
        assertEquals(listOf(WeightError.INVALID_WEIGHT_PRECISION), errors(ram, input(weight = "35.255")))
        assertEquals(listOf(WeightError.INVALID_SCROTAL_PRECISION), errors(ram, input(scrotal = "28.123")))
        assertIs<WeightValidation.Valid>(WeightRules.validate(ram, input(weight = "35.250")))
    }

    @Test
    fun scrotalCircumferenceRange() {
        assertEquals(listOf(WeightError.INVALID_SCROTAL_CIRCUMFERENCE), errors(ram, input(scrotal = "0")))
        assertEquals(listOf(WeightError.INVALID_SCROTAL_CIRCUMFERENCE), errors(ram, input(scrotal = "100")))
    }

    @Test
    fun cannotWeighBeforeBirthOrADeceasedAnimal() {
        assertEquals(listOf(WeightError.WEIGHT_DATE_BEFORE_BIRTH), errors(ram, input(date = LocalDate(2025, 12, 31))))
        assertEquals(listOf(WeightError.ANIMAL_DECEASED), errors(ram.copy(deceased = true), input()))
    }

    @Test
    fun reportsEveryProblemAtOnceSoTheFormCanMarkAllFields() {
        assertEquals(
            listOf(WeightError.INVALID_WEIGHT_KG, WeightError.SCROTAL_FEMALE_NOT_ALLOWED, WeightError.WEIGHT_DATE_BEFORE_BIRTH),
            errors(ewe, input(weight = "", scrotal = "20", date = LocalDate(2025, 1, 1))),
        )
    }

    private fun input(weight: String = "20", scrotal: String? = null, date: LocalDate = LocalDate(2026, 3, 2)) =
        WeightInput(milestone = WeightMilestone.WEANING, weightKg = weight, scrotalCircumferenceCm = scrotal, date = date)

    private fun errors(animal: AnimalForWeighing, input: WeightInput): List<WeightError> =
        assertIs<WeightValidation.Invalid>(WeightRules.validate(animal, input)).errors
}
