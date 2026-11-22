package com.agrotech.oviapp.weaning

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lista de destetes calculada contra SQLite real en memoria, sin red. */
class PendingWeaningsUseCaseTest {
    private val today = LocalDate(2026, 11, 22)
    private lateinit var driver: SqlDriver
    private lateinit var useCase: PendingWeaningsUseCase

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        useCase = PendingWeaningsUseCase(SqlDelightWeaningStore(OviAppDatabase(driver)), today = { today })
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun aLambWith75DaysAppearsInPending() {
        insertAnimal("a75", folio = "101", birth = "2026-09-08")

        val list = useCase.list(ranchId = "r1")

        assertEquals(listOf("101"), list.pending.map { it.folioInterno })
        assertEquals(LocalDate(2026, 11, 22), list.pending.single().scheduledDate)
        assertEquals(75, list.pending.single().ageDays)
        assertEquals(Sex.FEMALE, list.pending.single().sex)
    }

    @Test
    fun splitsPendingAndUpcomingAndLeavesOutTheRest() {
        insertAnimal("overdue", folio = "100", birth = "2026-09-01") // 82 días
        insertAnimal("due", folio = "101", birth = "2026-09-08") // 75 días
        insertAnimal("soon", folio = "102", birth = "2026-09-15") // vence en 7 días
        insertAnimal("young", folio = "103", birth = "2026-10-20") // vence en 42 días

        val list = useCase.list(ranchId = "r1", upcomingWindowDays = 14)

        assertEquals(listOf("100", "101"), list.pending.map { it.folioInterno })
        assertEquals(-7, list.pending.first().daysUntilWeaning)
        assertEquals(listOf("102"), list.upcoming.map { it.folioInterno })
        assertEquals(7, list.upcoming.single().daysUntilWeaning)
    }

    @Test
    fun ignoresWeanedSoldDeadAndOtherRanchAnimals() {
        insertAnimal("weaned", folio = "1", birth = "2026-08-01", weaningDate = "2026-10-15")
        insertAnimal("sold", folio = "2", birth = "2026-08-01", status = "SOLD")
        insertAnimal("dead", folio = "3", birth = "2026-08-01", mortalityDate = "2026-09-01")
        insertAnimal("other", folio = "4", birth = "2026-08-01", ranch = "r2")

        val list = useCase.list(ranchId = "r1")

        assertTrue(list.pending.isEmpty())
        assertTrue(list.upcoming.isEmpty())
    }

    @Test
    fun theRanchTargetMovesTheDates() {
        insertAnimal("a65", folio = "200", birth = "2026-09-18") // 65 días

        assertTrue(useCase.list(ranchId = "r1", targetDays = 75).pending.isEmpty())
        assertEquals(listOf("200"), useCase.list(ranchId = "r1", targetDays = 60).pending.map { it.folioInterno })
    }

    private fun insertAnimal(
        id: String,
        folio: String,
        birth: String,
        ranch: String = "r1",
        status: String = "ACTIVE",
        weaningDate: String? = null,
        mortalityDate: String? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO local_animals (id, ranch_id, folio_interno, breed, color, sex, birth_date, weaning_date,
                    mortality_date, life_stage, reproductive_status, status)
                VALUES (?, ?, ?, 'Dorper', 'Blanco', 'FEMALE', ?, ?, ?, 'LAMB_LACTATING', 'EMPTY', ?)
            """.trimIndent(),
            parameters = 7,
        ) {
            bindString(0, id)
            bindString(1, ranch)
            bindString(2, folio)
            bindString(3, birth)
            bindString(4, weaningDate)
            bindString(5, mortalityDate)
            bindString(6, status)
        }
    }
}
