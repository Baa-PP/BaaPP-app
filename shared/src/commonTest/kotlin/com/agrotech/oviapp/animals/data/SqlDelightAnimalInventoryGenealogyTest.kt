package com.agrotech.oviapp.animals.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightAnimalInventoryGenealogyTest {

    private lateinit var database: OviAppDatabase
    private lateinit var repository: SqlDelightAnimalInventoryRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        repository = SqlDelightAnimalInventoryRepository(database)
    }

    @Test
    fun testBidirectionalGenealogyResolvesParentsAndIncludesDeceasedOffspring() = runTest {
        val ranchId = "ranch-gen"
        val damId = UuidV7.generate()
        val sireId = UuidV7.generate()
        val activeLambId = UuidV7.generate()
        val deceasedLambId = UuidV7.generate()

        insertAnimal(
            id = damId,
            ranchId = ranchId,
            folio = "MADRE-01",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            birthDate = "2022-01-10"
        )
        insertAnimal(
            id = sireId,
            ranchId = ranchId,
            folio = "SEMENTAL-01",
            sex = Sex.MALE,
            lifeStage = LifeStage.ADULT,
            birthDate = "2021-06-05"
        )
        insertAnimal(
            id = activeLambId,
            ranchId = ranchId,
            folio = "CRIA-VIVA-01",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            birthDate = "2026-03-01",
            damId = damId,
            sireId = sireId,
            status = AnimalStatus.ACTIVE
        )
        insertAnimal(
            id = deceasedLambId,
            ranchId = ranchId,
            folio = "CRIA-FALL-02",
            sex = Sex.MALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            birthDate = "2025-11-15",
            damId = damId,
            sireId = sireId,
            status = AnimalStatus.DECEASED,
            mortalityDate = "2025-12-01",
            mortalityCause = "NEUMONIA"
        )

        // Child -> Parents
        val lambDetail = repository.getAnimalGenealogy(activeLambId)
        assertNotNull(lambDetail)
        assertEquals(damId, lambDetail.dam?.id)
        assertEquals("MADRE-01", lambDetail.dam?.folioInterno)
        assertEquals(sireId, lambDetail.sire?.id)
        assertEquals("SEMENTAL-01", lambDetail.sire?.folioInterno)

        // Dam -> Offspring (includes both active and deceased with status)
        val damDetail = repository.getAnimalGenealogy(damId)
        assertNotNull(damDetail)
        assertEquals(2, damDetail.offspring.size)
        assertEquals(activeLambId, damDetail.offspring[0].id)
        assertEquals(AnimalStatus.ACTIVE, damDetail.offspring[0].status)
        assertEquals(deceasedLambId, damDetail.offspring[1].id)
        assertEquals(AnimalStatus.DECEASED, damDetail.offspring[1].status)
        assertEquals("2025-12-01", damDetail.offspring[1].mortalityDateIso)
        assertEquals("NEUMONIA", damDetail.offspring[1].mortalityCause)

        // Sire -> Offspring (includes both active and deceased)
        val sireDetail = repository.getAnimalGenealogy(sireId)
        assertNotNull(sireDetail)
        assertEquals(2, sireDetail.offspring.size)
        assertTrue(sireDetail.offspring.any { it.id == deceasedLambId && it.status == AnimalStatus.DECEASED })

        // Deceased offspring -> Parents (bidirectional even for deceased)
        val deceasedDetail = repository.getAnimalGenealogy(deceasedLambId)
        assertNotNull(deceasedDetail)
        assertEquals(damId, deceasedDetail.dam?.id)
        assertEquals(sireId, deceasedDetail.sire?.id)
    }

    @Test
    fun testGetAnimalGenealogyReturnsNullForUnknownId() = runTest {
        assertNull(repository.getAnimalGenealogy("non-existent-id"))
    }

    @Test
    fun testGetAnimalGenealogyResolvesLatestLocalPhotoPath() = runTest {
        val animalId = UuidV7.generate()
        insertAnimal(
            id = animalId,
            ranchId = "ranch-photo",
            folio = "FOTO-01",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            birthDate = "2024-02-01"
        )
        database.oviAppDatabaseQueries.insertPendingSyncEvent(
            mutation_id = UuidV7.generate(),
            entity_type = "ANIMAL",
            operation = "CREATE",
            occurred_at = "2026-11-22T10:00:00Z",
            payload_json = """{"id":"$animalId","folioInterno":"FOTO-01"}""",
            local_photo_path = "/data/user/0/app/files/pending_animal_photos/first.jpg",
            sync_status = "PENDING"
        )
        database.oviAppDatabaseQueries.insertPendingSyncEvent(
            mutation_id = UuidV7.generate(),
            entity_type = "ANIMAL",
            operation = "UPDATE",
            occurred_at = "2026-11-22T12:00:00Z",
            payload_json = """{"id":"$animalId","folioInterno":"FOTO-01"}""",
            local_photo_path = "/data/user/0/app/files/pending_animal_photos/latest.jpg",
            sync_status = "PENDING"
        )

        val detail = repository.getAnimalGenealogy(animalId)
        assertNotNull(detail)
        assertEquals("/data/user/0/app/files/pending_animal_photos/latest.jpg", detail.localPhotoPath)
    }

    private fun insertAnimal(
        id: String,
        ranchId: String,
        folio: String,
        sex: Sex,
        lifeStage: LifeStage,
        birthDate: String,
        damId: String? = null,
        sireId: String? = null,
        status: AnimalStatus = AnimalStatus.ACTIVE,
        mortalityDate: String? = null,
        mortalityCause: String? = null
    ) {
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
            birth_date = birthDate,
            weaning_date = null,
            parturition_type = ParturitionType.SIMPLE.name,
            conception_method = ConceptionMethod.MN.name,
            sire_id = sireId,
            dam_id = damId,
            external_sire_folio = null,
            external_dam_folio = null,
            entry_reason = EntryReason.BIRTH.name,
            criador_name = null,
            photo_s3_key = null,
            birth_event_id = null,
            mortality_date = mortalityDate,
            mortality_cause = mortalityCause,
            life_stage = lifeStage.name,
            reproductive_status = ReproductiveStatus.EMPTY.name,
            status = status.name,
            updated_at = "2026-11-22T00:00:00Z"
        )
    }
}
