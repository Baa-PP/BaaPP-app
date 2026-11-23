package com.agrotech.oviapp.weaning

import com.agrotech.oviapp.domain.model.Sex
import kotlin.test.Test
import kotlin.test.assertEquals

class WeaningDestinationTest {

    @Test
    fun malesCannotGoToEweReplacement() {
        assertEquals(
            listOf(WeaningDestination.LAMB_FATTENING, WeaningDestination.SOLD),
            WeaningDestination.optionsFor(Sex.MALE),
        )
        assertEquals(WeaningDestination.entries, WeaningDestination.optionsFor(Sex.FEMALE))
    }
}
