package com.agrotech.oviapp.animals.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.animals.domain.model.InventoryTab
import com.agrotech.oviapp.animals.domain.model.RamInventoryItem
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalAgeCalculator
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightAnimalInventoryRepositoryTest {

    private lateinit var database: OviAppDatabase
    private lateinit var repository: SqlDelightAnimalInventoryRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        repository = SqlDelightAnimalInventoryRepository(database)
    }

    private fun insertTestAnimal(
        id: String = UuidV7.generate(),
        ranchId: String = "ranch-1",
        folioInterno: String,
        siniigaChip: String? = null,
        sex: Sex,
        lifeStage: LifeStage,
        reproductiveStatus: ReproductiveStatus,
        status: AnimalStatus = AnimalStatus.ACTIVE,
        birthDate: String = "2024-01-15",
        sireId: String? = null,
        damId: String? = null
    ): String {
        database.oviAppDatabaseQueries.insertAnimal(
            id = id,
            ranch_id = ranchId,
            folio_interno = folioInterno,
            siniiga_chip = siniigaChip,
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
            mortality_date = null,
            mortality_cause = null,
            life_stage = lifeStage.name,
            reproductive_status = reproductiveStatus.name,
            status = status.name,
            updated_at = "2026-11-22T00:00:00"
        )
        return id
    }

    @Test
    fun testDeceasedAnimalsDoNotAppearInOperativeLists() = runTest {
        val ranchId = "ranch-1"
        insertTestAnimal(
            folioInterno = "EWE-ACTIVE",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.ACTIVE
        )
        insertTestAnimal(
            folioInterno = "EWE-DECEASED",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.DECEASED
        )
        insertTestAnimal(
            folioInterno = "RAM-ACTIVE",
            sex = Sex.MALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.STUD_RAM,
            status = AnimalStatus.ACTIVE
        )
        insertTestAnimal(
            folioInterno = "RAM-DECEASED",
            sex = Sex.MALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.STUD_RAM,
            status = AnimalStatus.DECEASED
        )
        insertTestAnimal(
            folioInterno = "LAMB-ACTIVE",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.ACTIVE
        )
        insertTestAnimal(
            folioInterno = "LAMB-DECEASED",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.DECEASED
        )

        val ewes = repository.getEwesPaged(ranchId)
        val rams = repository.getRamsPaged(ranchId)
        val lambs = repository.getLambsPaged(ranchId)

        assertEquals(1, ewes.size)
        assertEquals("EWE-ACTIVE", ewes[0].folioInterno)

        assertEquals(1, rams.size)
        assertEquals("RAM-ACTIVE", rams[0].folioInterno)

        assertEquals(1, lambs.size)
        assertEquals("LAMB-ACTIVE", lambs[0].folioInterno)
    }

    @Test
    fun testSementalesTerminologyAndMetrics() = runTest {
        assertEquals("Sementales", InventoryTab.RAMS.title)

        val ranchId = "ranch-1"
        val ramId = insertTestAnimal(
            folioInterno = "RAM-01",
            sex = Sex.MALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.STUD_RAM
        )

        // Insert scrotal measurement
        database.oviAppDatabaseQueries.insertWeightMeasurement(
            id = UuidV7.generate(),
            ranch_id = ranchId,
            animal_id = ramId,
            milestone = "EVALUATION",
            weight_kg = 85.0,
            scrotal_circumference_cm = 36.5,
            measurement_date = "2026-10-01",
            notes = "Test scrotal",
            created_at = "2026-10-01T00:00:00",
            updated_at = "2026-10-01T00:00:00"
        )

        // Insert 2 live offspring and 1 deceased offspring sired by ramId
        insertTestAnimal(
            folioInterno = "LAMB-1",
            sex = Sex.MALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.ACTIVE,
            sireId = ramId
        )
        insertTestAnimal(
            folioInterno = "LAMB-2",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.LAMB_FATTENING,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.ACTIVE,
            sireId = ramId
        )
        insertTestAnimal(
            folioInterno = "LAMB-3-DEAD",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.LAMB_LACTATING,
            reproductiveStatus = ReproductiveStatus.EMPTY,
            status = AnimalStatus.DECEASED,
            sireId = ramId
        )

        val rams = repository.getRamsPaged(ranchId)
        assertEquals(1, rams.size)
        val ram = rams[0]
        assertEquals("RAM-01", ram.folioInterno)
        assertEquals(36.5, ram.latestScrotalCircumferenceCm)
        assertEquals(2, ram.activeOffspringCount)
        assertEquals(3, ram.totalOffspringCount)
    }

    @Test
    fun testEweFilteringByReproductiveStatusAndSearchQuery() = runTest {
        val ranchId = "ranch-1"
        insertTestAnimal(
            folioInterno = "BORREGA-101",
            siniigaChip = "484000000000101",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.EMPTY
        )
        insertTestAnimal(
            folioInterno = "BORREGA-102",
            siniigaChip = "484000000000102",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.PREGNANT
        )
        insertTestAnimal(
            folioInterno = "BORREGA-201",
            siniigaChip = "484000000000201",
            sex = Sex.FEMALE,
            lifeStage = LifeStage.ADULT,
            reproductiveStatus = ReproductiveStatus.LACTATING
        )

        // Filter by reproductive status
        val pregnantOnly = repository.getEwesPaged(ranchId, reproductiveStatus = ReproductiveStatus.PREGNANT)
        assertEquals(1, pregnantOnly.size)
        assertEquals("BORREGA-102", pregnantOnly[0].folioInterno)

        // Filter by folio query
        val query101 = repository.getEwesPaged(ranchId, query = "101")
        assertEquals(1, query101.size)
        assertEquals("BORREGA-101", query101[0].folioInterno)

        // Filter by siniiga chip query
        val chipQuery = repository.getEwesPaged(ranchId, query = "000201")
        assertEquals(1, chipQuery.size)
        assertEquals("BORREGA-201", chipQuery[0].folioInterno)
    }

    @Test
    fun testPagingOver1000AnimalsIsFastAndCorrect() = runTest {
        val ranchId = "ranch-bulk"
        for (i in 1..1050) {
            insertTestAnimal(
                folioInterno = "EWE-${i.toString().padStart(4, '0')}",
                sex = Sex.FEMALE,
                lifeStage = LifeStage.ADULT,
                reproductiveStatus = if (i % 2 == 0) ReproductiveStatus.EMPTY else ReproductiveStatus.PREGNANT,
                ranchId = ranchId
            )
        }

        val page1 = repository.getEwesPaged(ranchId = ranchId, limit = 50, offset = 0)
        assertEquals(50, page1.size)
        assertEquals("EWE-0001", page1.first().folioInterno)
        assertEquals("EWE-0050", page1.last().folioInterno)

        val page21 = repository.getEwesPaged(ranchId = ranchId, limit = 50, offset = 1000)
        assertEquals(50, page21.size)
        assertEquals("EWE-1001", page21.first().folioInterno)
        assertEquals("EWE-1050", page21.last().folioInterno)
    }
}
