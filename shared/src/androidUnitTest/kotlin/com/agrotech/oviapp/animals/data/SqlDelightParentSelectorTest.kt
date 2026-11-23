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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightParentSelectorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OviAppDatabase
    private lateinit var repository: SqlDelightParentSelectorRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        repository = SqlDelightParentSelectorRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun testDamSelectorOnlyListsActiveFemalesOfRanchAndSireSelectorOnlyActiveMales() = runTest {
        val ranchA = "ranch-a"
        val ranchB = "ranch-b"

        insertAnimal(ranchA, "HEMBRA-A1", Sex.FEMALE, AnimalStatus.ACTIVE)
        insertAnimal(ranchA, "HEMBRA-A2", Sex.FEMALE, AnimalStatus.ACTIVE)
        insertAnimal(
            ranchId = ranchA,
            folio = "HEMBRA-OTRA",
            sex = Sex.FEMALE,
            status = AnimalStatus.ACTIVE,
            siniigaChip = "484000999111"
        )
        insertAnimal(ranchA, "HEMBRA-A3-FALLECIDA", Sex.FEMALE, AnimalStatus.DECEASED)
        insertAnimal(ranchA, "CRIA-HEMBRA-LACT", Sex.FEMALE, AnimalStatus.ACTIVE, lifeStage = LifeStage.LAMB_LACTATING)
        insertAnimal(ranchA, "CRIA-MACHO-LACT", Sex.MALE, AnimalStatus.ACTIVE, lifeStage = LifeStage.LAMB_LACTATING)
        insertAnimal(ranchA, "SEMENTAL-A1", Sex.MALE, AnimalStatus.ACTIVE)
        insertAnimal(ranchB, "HEMBRA-B1", Sex.FEMALE, AnimalStatus.ACTIVE)
        insertAnimal(ranchB, "SEMENTAL-B1", Sex.MALE, AnimalStatus.ACTIVE)

        val damsA = repository.getPotentialDams(ranchA)
        assertEquals(3, damsA.size)
        assertTrue(damsA.all { it.sex == Sex.FEMALE && it.ranchId == ranchA })

        val siresA = repository.getPotentialSires(ranchA)
        assertEquals(1, siresA.size)
        assertEquals("SEMENTAL-A1", siresA[0].folioInterno)
        assertEquals(Sex.MALE, siresA[0].sex)
        assertEquals(ranchA, siresA[0].ranchId)

        val filteredByFolio = repository.getPotentialDams(ranchA, query = "A1")
        assertEquals(1, filteredByFolio.size)
        assertEquals("HEMBRA-A1", filteredByFolio[0].folioInterno)

        val filteredByChip = repository.getPotentialDams(ranchA, query = "484000999")
        assertEquals(1, filteredByChip.size)
        assertEquals("HEMBRA-OTRA", filteredByChip[0].folioInterno)

        val noMatches = repository.getPotentialDams(ranchA, query = "NO-EXISTE")
        assertTrue(noMatches.isEmpty())
    }

    private fun insertAnimal(
        ranchId: String,
        folio: String,
        sex: Sex,
        status: AnimalStatus,
        siniigaChip: String? = null,
        lifeStage: LifeStage = LifeStage.ADULT
    ) {
        database.oviAppDatabaseQueries.insertAnimal(
            id = UuidV7.generate(),
            ranch_id = ranchId,
            folio_interno = folio,
            siniiga_chip = siniigaChip,
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
            life_stage = lifeStage.name,
            reproductive_status = ReproductiveStatus.EMPTY.name,
            status = status.name,
            updated_at = "2026-11-22T00:00:00Z"
        )
    }
}
