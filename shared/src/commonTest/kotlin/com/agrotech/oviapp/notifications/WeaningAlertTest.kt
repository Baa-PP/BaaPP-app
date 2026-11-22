package com.agrotech.oviapp.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeaningAlertTest {

    @Test
    fun readsAWeaningAlertFromTheNotificationData() {
        val alert = WeaningAlert.fromData(
            mapOf("type" to "WEANING_DUE", "animalId" to "a1", "ranchId" to "r1", "folio" to "101"),
        )

        assertEquals(WeaningAlert(animalId = "a1", ranchId = "r1", folioInterno = "101"), alert)
    }

    @Test
    fun theFolioIsOptional() {
        val alert = WeaningAlert.fromData(mapOf("type" to "WEANING_DUE", "animalId" to "a1", "ranchId" to "r1"))

        assertEquals(WeaningAlert(animalId = "a1", ranchId = "r1", folioInterno = null), alert)
    }

    @Test
    fun ignoresOtherNotificationTypesOrIncompleteData() {
        assertNull(WeaningAlert.fromData(mapOf("type" to "OTHER", "animalId" to "a1", "ranchId" to "r1")))
        assertNull(WeaningAlert.fromData(mapOf("type" to "WEANING_DUE", "ranchId" to "r1")))
        assertNull(WeaningAlert.fromData(mapOf("type" to "WEANING_DUE", "animalId" to " ", "ranchId" to "r1")))
        assertNull(WeaningAlert.fromData(emptyMap()))
    }

    @Test
    fun roundTripsThroughTheDataMapUsedForTheTapIntent() {
        val alert = WeaningAlert(animalId = "a1", ranchId = "r1", folioInterno = "101")

        assertEquals(alert, WeaningAlert.fromData(alert.toData()))
    }
}
