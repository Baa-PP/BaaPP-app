package com.agrotech.oviapp.animals.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
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

    @Test
    fun testInvalidCalendarDatesRejectedBeforeDatabaseWrite() = runTest {
        val invalidBirth = AnimalFormState(
            ranchId = "ranch-val",
            folioInterno = "FOLIO-DATE-1",
            color = "Blanco",
            birthDate = "2025-02-30"
        )
        assertIs<AnimalSaveResult.ValidationError>(repository.saveAnimalOffline(invalidBirth))

        val nonDateBirth = invalidBirth.copy(birthDate = "abc")
        assertIs<AnimalSaveResult.ValidationError>(repository.saveAnimalOffline(nonDateBirth))

        val invalidWeaning = invalidBirth.copy(birthDate = "2024-02-29", weaningDate = "2024-04-31")
        assertIs<AnimalSaveResult.ValidationError>(repository.saveAnimalOffline(invalidWeaning))

        assertTrue(database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList().isEmpty())

        val validLeapDay = invalidBirth.copy(birthDate = "2024-02-29", weaningDate = null)
        assertIs<AnimalSaveResult.Success>(repository.saveAnimalOffline(validLeapDay))
    }

    @Test
    fun testInjectedClockUsedForUpdatedAtAndOccurredAtAndEditPreservesLifecycleFields() = runTest {
        // 1763769600000L = 2025-11-22T00:00:00Z
        val fixedRepo = SqlDelightAnimalCommandRepository(
            database = database,
            clockMillis = { 1763769600000L }
        )
        val animalId = UuidV7.generate()
        database.oviAppDatabaseQueries.insertAnimal(
            id = animalId,
            ranch_id = "ranch-life",
            folio_interno = "FOLIO-LIFE-1",
            siniiga_chip = null,
            registro_uno = null,
            adn = null,
            breed = "Dorper",
            color = "Blanco",
            sex = Sex.FEMALE.name,
            birth_date = "2024-01-10",
            weaning_date = null,
            parturition_type = ParturitionType.SIMPLE.name,
            conception_method = ConceptionMethod.MN.name,
            sire_id = null,
            dam_id = null,
            external_sire_folio = null,
            external_dam_folio = null,
            entry_reason = EntryReason.BIRTH.name,
            criador_name = null,
            photo_s3_key = "photos/existing.jpg",
            birth_event_id = "birth-ev-123",
            mortality_date = "2025-10-01",
            mortality_cause = "NATURAL",
            life_stage = LifeStage.ADULT.name,
            reproductive_status = ReproductiveStatus.EMPTY.name,
            status = "DECEASED",
            updated_at = "2025-10-01T00:00:00Z"
        )

        val editForm = AnimalFormState(
            id = animalId,
            ranchId = "ranch-life",
            folioInterno = "FOLIO-LIFE-2",
            breed = "Dorper",
            color = "Blanco",
            sex = Sex.FEMALE,
            birthDate = "2024-01-10",
            photoS3Key = "photos/existing.jpg"
        )
        val res = fixedRepo.saveAnimalOffline(editForm)
        assertIs<AnimalSaveResult.Success>(res)

        val reloaded = fixedRepo.getAnimalById(animalId)
        assertNotNull(reloaded)
        assertEquals("FOLIO-LIFE-2", reloaded.folioInterno)
        assertEquals("birth-ev-123", reloaded.birthEventId)
        assertEquals("2025-10-01", reloaded.mortalityDateIso)
        assertEquals("NATURAL", reloaded.mortalityCause)
        assertEquals("DECEASED", reloaded.status.name)
        assertEquals("photos/existing.jpg", reloaded.photoS3Key)
        assertEquals("2025-11-22T00:00:00Z", reloaded.updatedAtIso)

        val events = database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList()
        assertEquals(1, events.size)
        assertEquals("2025-11-22T00:00:00Z", events[0].occurred_at)
    }

    @Test
    fun testSelfParentingRejectedByValidation() = runTest {
        val selfId = UuidV7.generate()
        val selfDam = AnimalFormState(
            id = selfId,
            ranchId = "ranch-self",
            folioInterno = "SELF-1",
            color = "Blanco",
            birthDate = "2024-01-01",
            damId = selfId
        )
        assertIs<AnimalSaveResult.ValidationError>(repository.saveAnimalOffline(selfDam))

        val selfSire = selfDam.copy(damId = null, sireId = selfId)
        assertIs<AnimalSaveResult.ValidationError>(repository.saveAnimalOffline(selfSire))
    }

    @Test
    fun testReadOnlySessionCannotEditAnimalOffline() = runTest {
        val readOnlyStorage = object : SessionStorage {
            private var session: AuthSession? = AuthSession(
                accessToken = "tok",
                username = "auditor",
                role = "ASSOCIATION_SUPERUSER",
                ranchId = "ranch-ro",
                sessionId = "s-ro"
            )
            override fun saveSession(session: AuthSession) { this.session = session }
            override fun getSession(): AuthSession? = session
            override fun clearSession() { session = null }
            override fun hasSession(): Boolean = session != null
            override fun updateSessionIfMatches(
                expectedSessionId: String,
                expectedRefreshToken: String?,
                expectedAccessToken: String?,
                transform: (AuthSession) -> AuthSession
            ): Boolean = false
            override fun clearSessionIfMatches(
                expectedSessionId: String,
                expectedRefreshToken: String?,
                expectedAccessToken: String?
            ): Boolean = false
        }
        val gatedRepo = SqlDelightAnimalCommandRepository(
            database = database,
            sessionStorage = readOnlyStorage
        )
        val animalId = UuidV7.generate()
        database.oviAppDatabaseQueries.insertAnimal(
            id = animalId,
            ranch_id = "ranch-ro",
            folio_interno = "ORIG-1",
            siniiga_chip = null,
            registro_uno = null,
            adn = null,
            breed = "Dorper",
            color = "Blanco",
            sex = Sex.FEMALE.name,
            birth_date = "2024-01-01",
            weaning_date = null,
            parturition_type = ParturitionType.SIMPLE.name,
            conception_method = ConceptionMethod.MN.name,
            sire_id = null,
            dam_id = null,
            external_sire_folio = null,
            external_dam_folio = null,
            entry_reason = EntryReason.BIRTH.name,
            criador_name = null,
            photo_s3_key = null,
            birth_event_id = null,
            mortality_date = null,
            mortality_cause = null,
            life_stage = LifeStage.ADULT.name,
            reproductive_status = ReproductiveStatus.EMPTY.name,
            status = "ACTIVE",
            updated_at = "2025-01-01T00:00:00Z"
        )

        val editAttempt = AnimalFormState(
            id = animalId,
            ranchId = "ranch-ro",
            folioInterno = "MODIFIED-1",
            color = "Blanco",
            birthDate = "2024-01-01"
        )
        val result = gatedRepo.saveAnimalOffline(editAttempt)
        assertIs<AnimalSaveResult.ValidationError>(result)
        assertEquals("ORIG-1", gatedRepo.getAnimalById(animalId)?.folioInterno)
        assertTrue(database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList().isEmpty())
    }

    @Test
    fun testPotentialParentsRetrievalReturnsAllBeyond200() = runTest {
        val ranchId = "ranch-many"
        var lastId = ""
        for (i in 1..205) {
            val id = UuidV7.generate()
            lastId = id
            val padded = i.toString().padStart(3, '0')
            database.oviAppDatabaseQueries.insertAnimal(
                id = id,
                ranch_id = ranchId,
                folio_interno = "DAM-$padded",
                siniiga_chip = null,
                registro_uno = null,
                adn = null,
                breed = "Pelifolk",
                color = "Blanco",
                sex = Sex.FEMALE.name,
                birth_date = "2023-01-01",
                weaning_date = null,
                parturition_type = ParturitionType.SIMPLE.name,
                conception_method = ConceptionMethod.MN.name,
                sire_id = null,
                dam_id = null,
                external_sire_folio = null,
                external_dam_folio = null,
                entry_reason = EntryReason.BIRTH.name,
                criador_name = null,
                photo_s3_key = null,
                birth_event_id = null,
                mortality_date = null,
                mortality_cause = null,
                life_stage = LifeStage.ADULT.name,
                reproductive_status = ReproductiveStatus.EMPTY.name,
                status = "ACTIVE",
                updated_at = "2025-01-01T00:00:00Z"
            )
        }

        val dams = repository.getPotentialDams(ranchId)
        assertEquals(205, dams.size)
        assertTrue(dams.any { it.first == lastId && it.second == "DAM-205" })
    }

    @Test
    fun testEditMissingAnimalReturnsErrorWithoutEnqueueingEvent() = runTest {
        val missingEdit = AnimalFormState(
            id = UuidV7.generate(),
            ranchId = "ranch-missing",
            folioInterno = "MISSING-1",
            color = "Blanco",
            birthDate = "2024-01-01"
        )
        val result = repository.saveAnimalOffline(missingEdit)
        assertIs<AnimalSaveResult.Error>(result)
        assertTrue(database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList().isEmpty())
    }
}
