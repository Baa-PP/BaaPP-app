package com.agrotech.oviapp.births.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.model.LiveOffspringDraft
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
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
import kotlin.test.assertTrue

class SqlDelightBirthEventRepositoryTest {

    private lateinit var database: OviAppDatabase
    private lateinit var repository: SqlDelightBirthEventRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        repository = SqlDelightBirthEventRepository(
            database = database,
            photoExistsCheck = { true }
        )
    }

    @Test
    fun testCannotSaveBirthEventWithoutPhoto() = runTest {
        val ranchId = "ranch-birth"
        val damId = insertAdult(ranchId, "MADRE-01", Sex.FEMALE)

        val draftWithoutPhoto = BirthEventDraft(
            ranchId = ranchId,
            damId = damId,
            birthDateIso = "2026-11-20",
            stillborns = 0,
            liveOffspring = listOf(
                LiveOffspringDraft("CRIA-01", Sex.FEMALE, "Blanco", "3.4")
            ),
            localPhotoPath = null
        )

        val result = repository.saveBirthEventOffline(draftWithoutPhoto, "2026-11-22")
        assertIs<BirthSaveResult.ValidationError>(result)
        assertTrue(result.message.contains("fotografía", ignoreCase = true))
        assertTrue(database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList().isEmpty())
    }

    @Test
    fun testOfflineBirthCreatesLinkedOffspringWeightsAndSyncEvent() = runTest {
        val ranchId = "ranch-offline-birth"
        val damId = insertAdult(ranchId, "MADRE-10", Sex.FEMALE)
        val sireId = insertAdult(ranchId, "SEMENTAL-05", Sex.MALE)

        val draft = BirthEventDraft(
            ranchId = ranchId,
            damId = damId,
            sireId = sireId,
            conceptionMethod = ConceptionMethod.IA,
            birthDateIso = "2026-11-20",
            stillborns = 1,
            liveOffspring = listOf(
                LiveOffspringDraft("CRIA-10-A", Sex.FEMALE, "Blanco", "3.2"),
                LiveOffspringDraft("CRIA-10-B", Sex.MALE, "Pinto", "3.6")
            ),
            localPhotoPath = "/data/user/0/app/files/pending_birth_photos/birth_1.jpg"
        )

        assertEquals(ParturitionType.TRIPLET, draft.deducedParturitionType())

        val result = repository.saveBirthEventOffline(draft, "2026-11-22")
        val success = assertIs<BirthSaveResult.Success>(result)
        assertEquals(2, success.offspringIds.size)

        val birthEvent = database.oviAppDatabaseQueries
            .selectBirthEventById(success.birthEventId)
            .executeAsOneOrNull()
        assertEquals(2L, birthEvent?.live_births)
        assertEquals(1L, birthEvent?.stillborns)
        assertEquals(ParturitionType.TRIPLET.name, birthEvent?.parturition_type)
        assertEquals("/data/user/0/app/files/pending_birth_photos/birth_1.jpg", birthEvent?.local_photo_path)

        val linkedOffspring = database.oviAppDatabaseQueries
            .selectOffspringByParent(damId)
            .executeAsList()
        assertEquals(2, linkedOffspring.size)
        assertTrue(linkedOffspring.all { it.birth_event_id == success.birthEventId })

        val weights = database.oviAppDatabaseQueries
            .selectWeightsByAnimalPaged(success.offspringIds[0], 10, 0)
            .executeAsList()
        assertEquals(1, weights.size)
        assertEquals("BIRTH", weights[0].milestone)
        assertEquals(3.2, weights[0].weight_kg)

        val syncEvents = database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList()
        assertEquals(1, syncEvents.size)
        assertEquals("BIRTH_EVENT", syncEvents[0].entity_type)
        assertEquals("CREATE", syncEvents[0].operation)
        assertEquals("/data/user/0/app/files/pending_birth_photos/birth_1.jpg", syncEvents[0].local_photo_path)
    }

    @Test
    fun testDuplicateFolioInRanchRejectedWithoutPartialWrites() = runTest {
        val ranchId = "ranch-dup-birth"
        val damId = insertAdult(ranchId, "MADRE-20", Sex.FEMALE)
        insertAdult(ranchId, "FOLIO-EXISTENTE", Sex.FEMALE)

        val draft = BirthEventDraft(
            ranchId = ranchId,
            damId = damId,
            birthDateIso = "2026-11-20",
            liveOffspring = listOf(
                LiveOffspringDraft("CRIA-NUEVA", Sex.FEMALE, "Blanco", "3.1"),
                LiveOffspringDraft("FOLIO-EXISTENTE", Sex.MALE, "Negro", "3.5")
            ),
            localPhotoPath = "/tmp/birth.jpg"
        )

        val result = repository.saveBirthEventOffline(draft, "2026-11-22")
        assertIs<BirthSaveResult.DuplicateFolio>(result)
        assertTrue(database.oviAppDatabaseQueries.selectBirthEventsByDam(damId).executeAsList().isEmpty())
        assertTrue(database.oviAppDatabaseQueries.selectPendingSyncEvents().executeAsList().isEmpty())
        assertEquals(
            null,
            database.oviAppDatabaseQueries.selectAnimalByFolio(ranchId, "CRIA-NUEVA").executeAsOneOrNull()
        )
        assertTrue(database.oviAppDatabaseQueries.selectOffspringByParent(damId).executeAsList().isEmpty())
    }

    @Test
    fun testBirthDateWindowAndWeightValidationAndMissingPhotoFile() = runTest {
        val ranchId = "ranch-val"
        val damId = insertAdult(ranchId, "MADRE-VAL", Sex.FEMALE)

        // Day 30 inclusive is valid ("2026-10-23" when today is "2026-11-22")
        val day30Draft = BirthEventDraft(
            ranchId = ranchId,
            damId = damId,
            sireId = "   ", // whitespace normalized to null
            birthDateIso = "2026-10-23",
            liveOffspring = listOf(LiveOffspringDraft("CRIA-30", Sex.FEMALE, "Blanco", "3.0")),
            localPhotoPath = "/tmp/valid.jpg"
        )
        val day30Res = repository.saveBirthEventOffline(day30Draft, "2026-11-22")
        assertIs<BirthSaveResult.Success>(day30Res)
        assertEquals(
            null,
            database.oviAppDatabaseQueries.selectBirthEventById(day30Res.birthEventId).executeAsOne().sire_id
        )

        // Day 31 rejected
        val day31Draft = day30Draft.copy(
            birthDateIso = "2026-10-22",
            liveOffspring = listOf(LiveOffspringDraft("CRIA-31", Sex.FEMALE, "Blanco", "3.0"))
        )
        assertIs<BirthSaveResult.ValidationError>(repository.saveBirthEventOffline(day31Draft, "2026-11-22"))

        // Non-finite weight rejected
        val nanWeight = day30Draft.copy(
            liveOffspring = listOf(LiveOffspringDraft("CRIA-NAN", Sex.FEMALE, "Blanco", "NaN"))
        )
        assertIs<BirthSaveResult.ValidationError>(repository.saveBirthEventOffline(nanWeight, "2026-11-22"))

        // Missing/unreadable photo file rejected before writes
        val strictRepo = SqlDelightBirthEventRepository(
            database = database,
            photoExistsCheck = { false }
        )
        val missingFileRes = strictRepo.saveBirthEventOffline(
            day30Draft.copy(liveOffspring = listOf(LiveOffspringDraft("CRIA-MISS", Sex.FEMALE, "Blanco", "3.0"))),
            "2026-11-22"
        )
        assertIs<BirthSaveResult.ValidationError>(missingFileRes)
    }

    private fun insertAdult(ranchId: String, folio: String, sex: Sex): String {
        val id = UuidV7.generate()
        database.oviAppDatabaseQueries.insertAnimal(
            id = id,
            ranch_id = ranchId,
            folio_interno = folio,
            siniiga_chip = null,
            registro_uno = null,
            adn = null,
            breed = "Pelifolk",
            color = "Blanco",
            sex = sex.name,
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
            status = AnimalStatus.ACTIVE.name,
            updated_at = "2026-11-22T00:00:00Z"
        )
        return id
    }
}
