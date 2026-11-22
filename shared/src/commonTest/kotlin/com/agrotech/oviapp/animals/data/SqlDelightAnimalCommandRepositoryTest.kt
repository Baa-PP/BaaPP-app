package com.agrotech.oviapp.animals.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightAnimalCommandRepositoryTest {

    private lateinit var database: OviAppDatabase
    private lateinit var repository: SqlDelightAnimalCommandRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        repository = SqlDelightAnimalCommandRepository(database)
    }

    @Test
    fun testOfflineCreationEnqueuesPendingSyncEventWithoutDataLoss() = runTest {
        val form = AnimalFormState(
            ranchId = "ranch-offline",
            folioInterno = "AIR-2026-H",
            siniigaChip = "484123456789012",
            registroUno = "REG-999",
            adn = "ADN-001",
            breed = "Pelifolk",
            color = "Blanco",
            sex = Sex.FEMALE,
            birthDate = "2025-05-10",
            entryReason = EntryReason.BIRTH,
            criadorName = "Rancho Las Aguilas",
            localPhotoPath = "/storage/emulated/0/DCIM/animal_photo.jpg"
        )

        val result = repository.saveAnimalOffline(form)
        assertIs<AnimalSaveResult.Success>(result)
        assertTrue(UuidV7.isValid(result.animalId))
        assertTrue(UuidV7.isValid(result.mutationId))

        // Check local_animals table
        val saved = repository.getAnimalById(result.animalId)
        assertNotNull(saved)
        assertEquals("AIR-2026-H", saved.folioInterno)
        assertEquals("484123456789012", saved.siniigaChip)
        assertEquals("Rancho Las Aguilas", saved.criadorName)

        // Check pending_sync_events table
        val pendingEvents = database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList()
        assertEquals(1, pendingEvents.size)
        assertEquals(result.mutationId, pendingEvents[0].mutation_id)
        assertEquals("ANIMAL", pendingEvents[0].entity_type)
        assertEquals("CREATE", pendingEvents[0].operation)
        assertEquals("/storage/emulated/0/DCIM/animal_photo.jpg", pendingEvents[0].local_photo_path)
    }

    @Test
    fun testDuplicateFolioInSameRanchIsRejectedLocally() = runTest {
        val ranchId = "ranch-dup"
        val first = AnimalFormState(
            ranchId = ranchId,
            folioInterno = "ARETE-10",
            color = "Café",
            birthDate = "2025-01-01"
        )
        val result1 = repository.saveAnimalOffline(first)
        assertIs<AnimalSaveResult.Success>(result1)

        val duplicate = AnimalFormState(
            ranchId = ranchId,
            folioInterno = "  ARETE-10  ", // same folio with whitespace
            color = "Negro",
            birthDate = "2025-02-01"
        )
        val result2 = repository.saveAnimalOffline(duplicate)
        assertIs<AnimalSaveResult.DuplicateFolio>(result2)
        assertTrue(result2.message.contains("ARETE-10"))
    }

    @Test
    fun testFolioIsEditableLocallyWithoutBreakingRelationships() = runTest {
        val ranchId = "ranch-edit"
        val original = AnimalFormState(
            ranchId = ranchId,
            folioInterno = "ARETE-OLD",
            color = "Blanco",
            birthDate = "2024-03-15"
        )
        val createRes = repository.saveAnimalOffline(original)
        assertIs<AnimalSaveResult.Success>(createRes)

        // Now edit folio to ARETE-NEW
        val editForm = original.copy(
            id = createRes.animalId,
            folioInterno = "ARETE-NEW"
        )
        val updateRes = repository.saveAnimalOffline(editForm)
        assertIs<AnimalSaveResult.Success>(updateRes)

        val updated = repository.getAnimalById(createRes.animalId)
        assertNotNull(updated)
        assertEquals(createRes.animalId, updated.id, "ID must remain unchanged across folio edits")
        assertEquals("ARETE-NEW", updated.folioInterno)

        val events = database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList()
        assertEquals(2, events.size)
        assertEquals("UPDATE", events[1].operation)
    }

    @Test
    fun testInvalidSiniigaRejectedByFormValidation() = runTest {
        val form = AnimalFormState(
            ranchId = "ranch-val",
            folioInterno = "FOLIO-1",
            siniigaChip = "12345", // invalid prefix and length
            color = "Blanco",
            birthDate = "2025-01-01"
        )
        val result = repository.saveAnimalOffline(form)
        assertIs<AnimalSaveResult.ValidationError>(result)
        assertTrue(result.message.contains("SINIIGA"))
    }
}
