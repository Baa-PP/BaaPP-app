package com.agrotech.oviapp.android.ui.theme

import com.agrotech.oviapp.domain.model.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FieldDesignTest {

    private val outdoorMinimum = FieldContrast.OUTDOOR_MINIMUM_RATIO

    @Test
    fun captureTargetsAreAtLeast56dp() {
        assertTrue(FieldDimens.MIN_TOUCH_TARGET_DP >= 56)
        assertTrue(FieldDimens.CAPTURE_BUTTON_HEIGHT_DP >= FieldDimens.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21.0, FieldContrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, FieldContrast.ratio(0xFF777777, 0xFF777777), 0.01)
        assertEquals(4.48, FieldContrast.ratio(0xFF777777, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun everyTextAndBackgroundPairMeetsTheOutdoorContrast() {
        FieldPalette.textPairs.forEach { (name, pair) ->
            val ratio = FieldContrast.ratio(pair.first, pair.second)
            assertTrue(ratio >= outdoorMinimum, "$name tiene contraste $ratio, se pide >= $outdoorMinimum")
        }
    }

    @Test
    fun sexIdentityUsesPinkOrBlueTogetherWithTextAndSymbol() {
        val female = SexIdentity.of(Sex.FEMALE)
        val male = SexIdentity.of(Sex.MALE)

        assertEquals("Hembra", female.label)
        assertEquals("Macho", male.label)
        assertEquals("♀", female.symbol)
        assertEquals("♂", male.symbol)
        assertEquals(FieldPalette.FEMALE, female.content)
        assertEquals(FieldPalette.MALE, male.content)
        assertNotEquals(female.container, male.container)
    }

    @Test
    fun vocabularyUsesTheRancherWords() {
        assertEquals("Arete", FieldVocabulary.EAR_TAG)
        assertEquals("Semental", FieldVocabulary.STUD_RAM)
        assertEquals("Vientre", FieldVocabulary.BREEDING_EWE)
        assertEquals("Destete", FieldVocabulary.WEANING)
        assertEquals("Cría", FieldVocabulary.LAMB)
    }
}
