package com.agrotech.oviapp.summary

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.animals.data.SqlDelightAnimalInventoryRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Resumen del rancho calculado solo con SQLite (sin red) y comparado contra el propio inventario. */
class RanchSummaryUseCaseTest {
    private val today = LocalDate(2026, 11, 22)
    private lateinit var driver: SqlDriver
    private lateinit var database: OviAppDatabase
    private lateinit var useCase: RanchSummaryUseCase

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        useCase = RanchSummaryUseCase(database, today = { today })

        // Vientres adultos
        insert("e1", "FEMALE", "ADULT", reproductive = "PREGNANT")
        insert("e2", "FEMALE", "ADULT", reproductive = "PREGNANT")
        insert("e3", "FEMALE", "ADULT", reproductive = "LACTATING")
        insert("e4", "FEMALE", "ADULT", reproductive = "EMPTY")
        // Sementales
        insert("r1", "MALE", "ADULT", reproductive = "STUD_RAM")
        // Crías: una ya cumplió 75 días (destete pendiente), otra es recién nacida
        insert("l1", "FEMALE", "LAMB_LACTATING", birth = "2026-09-01")
        insert("l2", "MALE", "LAMB_LACTATING", birth = "2026-11-10")
        insert("l3", "MALE", "LAMB_FATTENING", birth = "2026-06-01", weaning = "2026-08-15")
        insert("l4", "FEMALE", "EWE_REPLACEMENT", birth = "2026-05-01", weaning = "2026-07-15")
        // Bajas: no cuentan como activos; las muertes del mes sí en mortalidad
        insert("d1", "FEMALE", "LAMB_LACTATING", status = "DECEASED", mortality = "2026-11-05")
        insert("d2", "MALE", "ADULT", status = "DECEASED", mortality = "2026-11-30")
        insert("d3", "FEMALE", "ADULT", status = "DECEASED", mortality = "2026-10-31")
        insert("s1", "FEMALE", "ADULT", status = "SOLD", reproductive = "PREGNANT")
        // Otro rancho
        insert("x1", "FEMALE", "ADULT", ranch = "r2", reproductive = "PREGNANT")
        insert("x2", "MALE", "ADULT", ranch = "r2", status = "DECEASED", mortality = "2026-11-10")
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun countsEachCardFromTheLocalDatabase() {
        val summary = useCase.summary(ranchId = "r1")

        assertEquals(
            RanchSummary(
                activeAnimals = 9,
                ewes = 4,
                pregnantEwes = 2,
                lactatingEwes = 1,
                emptyEwes = 1,
                rams = 1,
                lambs = 4,
                lactatingLambs = 2,
                fatteningLambs = 1,
                replacementLambs = 1,
                pendingWeanings = 1,
                deathsThisMonth = 2,
            ),
            summary,
        )
    }

    @Test
    fun theCountsMatchTheInventoryLists() = runTest {
        val inventory = SqlDelightAnimalInventoryRepository(database, Dispatchers.Unconfined)
        val summary = useCase.summary(ranchId = "r1")

        assertEquals(inventory.getEwesPaged("r1", limit = 1000).size.toLong(), summary.ewes)
        assertEquals(inventory.getEwesPaged("r1", reproductiveStatus = ReproductiveStatus.PREGNANT, limit = 1000).size.toLong(), summary.pregnantEwes)
        assertEquals(inventory.getEwesPaged("r1", reproductiveStatus = ReproductiveStatus.LACTATING, limit = 1000).size.toLong(), summary.lactatingEwes)
        assertEquals(inventory.getEwesPaged("r1", reproductiveStatus = ReproductiveStatus.EMPTY, limit = 1000).size.toLong(), summary.emptyEwes)
        assertEquals(inventory.getRamsPaged("r1", limit = 1000).size.toLong(), summary.rams)
        assertEquals(inventory.getLambsPaged("r1", limit = 1000).size.toLong(), summary.lambs)
        assertEquals(inventory.getLambsPaged("r1", lifeStage = LifeStage.LAMB_LACTATING, limit = 1000).size.toLong(), summary.lactatingLambs)
        assertEquals(inventory.getLambsPaged("r1", lifeStage = LifeStage.LAMB_FATTENING, limit = 1000).size.toLong(), summary.fatteningLambs)
        assertEquals(inventory.getLambsPaged("r1", lifeStage = LifeStage.EWE_REPLACEMENT, limit = 1000).size.toLong(), summary.replacementLambs)
    }

    @Test
    fun theRanchWeaningTargetChangesThePendingCount() {
        // Con meta de 60 días, la cría nacida el 2026-09-01 (82 días) sigue pendiente y ninguna otra entra.
        assertEquals(1, useCase.summary(ranchId = "r1", weaningTargetDays = 60).pendingWeanings)
        // Con 90 días todavía no le toca a nadie.
        assertEquals(0, useCase.summary(ranchId = "r1", weaningTargetDays = 90).pendingWeanings)
    }

    @Test
    fun anEmptyRanchShowsZeros() {
        assertEquals(RanchSummary.EMPTY, useCase.summary(ranchId = "sin-animales"))
    }

    private fun insert(
        id: String,
        sex: String,
        stage: String,
        ranch: String = "r1",
        status: String = "ACTIVE",
        reproductive: String = "EMPTY",
        birth: String = "2023-01-10",
        weaning: String? = null,
        mortality: String? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO local_animals (id, ranch_id, folio_interno, breed, color, sex, birth_date, weaning_date,
                    mortality_date, mortality_cause, life_stage, reproductive_status, status)
                VALUES (?, ?, ?, 'Dorper', 'Blanco', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 11,
        ) {
            bindString(0, id)
            bindString(1, ranch)
            bindString(2, "$id-folio")
            bindString(3, sex)
            bindString(4, birth)
            bindString(5, weaning)
            bindString(6, mortality)
            bindString(7, mortality?.let { "Neumonía" })
            bindString(8, stage)
            bindString(9, reproductive)
            bindString(10, status)
        }
    }
}
