package com.agrotech.oviapp.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun theIdentityIsStablePerLambAndNeverAmbiguous() {
        // El folio no forma parte de la identidad: avisos repetidos del mismo cordero se reemplazan.
        assertEquals(alert(ranch = "r1", animal = "a1").identity, alert(ranch = "r1", animal = "a1").copy(folioInterno = "101").identity)
        // Sin escapar, rancho "a/b" + animal "c" y rancho "a" + animal "b/c" darían el mismo "a/b/c".
        assertNotEquals(alert(ranch = "a/b", animal = "c").identity, alert(ranch = "a", animal = "b/c").identity)
        assertNotEquals(alert(ranch = "a%2Fb", animal = "c").identity, alert(ranch = "a/b", animal = "c").identity)
    }

    @Test
    fun roundTripsThroughTheDataMapUsedForTheTapIntent() {
        val alert = WeaningAlert(animalId = "a1", ranchId = "r1", folioInterno = "101")

        assertEquals(alert, WeaningAlert.fromData(alert.toData()))
    }

    private fun alert(ranch: String, animal: String) = WeaningAlert(animalId = animal, ranchId = ranch, folioInterno = null)
}
