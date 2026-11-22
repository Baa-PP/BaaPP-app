package com.agrotech.oviapp.weights

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Captura completa contra SQLite real en memoria: sin red, todo sale de la base local. */
class RecordWeightUseCaseTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: OviAppDatabase
    private lateinit var useCase: RecordWeightUseCase
    private lateinit var queue: PendingSyncQueue
    private var nextId = 0
    private var nextMutation = 0
    private var failNextEnqueue = false

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        queue = PendingSyncQueue(database, nowMillis = { 1_795_000_000_000 }, newMutationId = {
            // Falla justo después del INSERT del pesaje, dentro de la transacción de la cola.
            if (failNextEnqueue) {
                failNextEnqueue = false
                error("Sin espacio para encolar")
            }
            "m${++nextMutation}"
        })
        useCase = RecordWeightUseCase(
            store = SqlDelightWeightStore(database, queue),
            newId = { "w${++nextId}" },
            now = { "2026-11-22T12:00:00Z" },
        )
        insertAnimal(id = "ram", sex = "MALE")
        insertAnimal(id = "ewe", sex = "FEMALE")
        insertAnimal(id = "dead", sex = "FEMALE", status = "DECEASED")
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun savesTheWeighingAndReturnsItsGainAgainstThePreviousOne() {
        useCase.record("ram", input(WeightMilestone.BIRTH, "4", LocalDate(2026, 1, 1)))

        val result = useCase.record("ram", input(WeightMilestone.WEANING, "19.00", LocalDate(2026, 3, 2), scrotal = "18.5"))

        val entry = assertIs<RecordWeightResult.Recorded>(result).entry
        assertEquals("w2", entry.record.id)
        assertEquals(60, entry.ageDays)
        assertEquals(250, entry.dailyGainGrams)
        assertEquals(1850, entry.record.scrotalHundredths)

        val row = database.oviAppDatabaseQueries.selectLatestWeightByAnimal("ram").executeAsOne()
        assertEquals("r1", row.ranch_id)
        assertEquals("WEANING", row.milestone)
        assertEquals(19.0, row.weight_kg)
        assertEquals(18.5, row.scrotal_circumference_cm)
        assertEquals("2026-03-02", row.measurement_date)
    }

    @Test
    fun eachWeighingIsQueuedForTheServerWithTheRegisterWeightFields() {
        val result = useCase.record("ram", input(WeightMilestone.WEANING, "19.25", LocalDate(2026, 3, 2), scrotal = "18.5"))
        val id = assertIs<RecordWeightResult.Recorded>(result).entry.record.id

        val event = queue.pending().single()
        assertEquals(SyncEntityType.WEIGHT_MEASUREMENT, event.entityType)
        assertEquals(SyncOperation.CREATE, event.operation)
        // Mismos campos que POST /api/v1/weights (RegisterWeightRequest); el id local hace idempotente el reenvío.
        val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
        assertEquals(id, payload["id"]!!.jsonPrimitive.content)
        assertEquals("ram", payload["animalId"]!!.jsonPrimitive.content)
        assertEquals("WEANING", payload["milestone"]!!.jsonPrimitive.content)
        assertEquals("19.25", payload["weightKg"]!!.jsonPrimitive.content)
        assertEquals("18.5", payload["scrotalCircumferenceCm"]!!.jsonPrimitive.content)
        assertEquals("2026-03-02", payload["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedEnqueueLeavesNothingAndTheRetrySavesExactlyOnce() {
        failNextEnqueue = true

        assertFailsWith<IllegalStateException> {
            useCase.record("ram", input(WeightMilestone.BIRTH, "4", LocalDate(2026, 1, 1)))
        }
        assertNull(database.oviAppDatabaseQueries.selectLatestWeightByAnimal("ram").executeAsOneOrNull())
        assertEquals(0, queue.pendingCount())

        assertIs<RecordWeightResult.Recorded>(useCase.record("ram", input(WeightMilestone.BIRTH, "4", LocalDate(2026, 1, 1))))
        assertEquals(1, database.oviAppDatabaseQueries.selectWeightsByAnimalChronological("ram").executeAsList().size)
        assertEquals(1, queue.pendingCount())
    }

    @Test
    fun historyIsRebuiltFromSqliteInChronologicalOrder() {
        useCase.record("ram", input(WeightMilestone.DAY_120, "34", LocalDate(2026, 5, 1)))
        useCase.record("ram", input(WeightMilestone.BIRTH, "4", LocalDate(2026, 1, 1)))

        val history = useCase.history("ram")!!

        assertEquals(listOf(WeightMilestone.BIRTH, WeightMilestone.DAY_120), history.entries.map { it.record.milestone })
        assertEquals(250, history.overallDailyGainGrams)
    }

    @Test
    fun aFemaleCannotGetScrotalCircumferenceAndNothingIsSaved() {
        val result = useCase.record("ewe", input(WeightMilestone.WEANING, "18", LocalDate(2026, 3, 2), scrotal = "15"))

        assertEquals(listOf(WeightError.SCROTAL_FEMALE_NOT_ALLOWED), assertIs<RecordWeightResult.Rejected>(result).errors)
        assertNull(database.oviAppDatabaseQueries.selectLatestWeightByAnimal("ewe").executeAsOneOrNull())
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun deceasedOrUnknownAnimals() {
        val dead = useCase.record("dead", input(WeightMilestone.EVALUATION, "40", LocalDate(2026, 6, 1)))

        assertEquals(listOf(WeightError.ANIMAL_DECEASED), assertIs<RecordWeightResult.Rejected>(dead).errors)
        assertIs<RecordWeightResult.AnimalNotFound>(useCase.record("ghost", input(WeightMilestone.BIRTH, "4", LocalDate(2026, 1, 1))))
        assertNull(useCase.history("ghost"))
    }

    private fun input(milestone: WeightMilestone, weight: String, date: LocalDate, scrotal: String? = null) =
        WeightInput(milestone = milestone, weightKg = weight, scrotalCircumferenceCm = scrotal, date = date)

    private fun insertAnimal(id: String, sex: String, status: String = "ACTIVE") {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO local_animals (id, ranch_id, folio_interno, breed, color, sex, birth_date, life_stage, reproductive_status, status)
                VALUES ('$id', 'r1', '$id-folio', 'Dorper', 'Blanco', '$sex', '2026-01-01', 'LAMB_LACTATING', 'EMPTY', '$status')
            """.trimIndent(),
            parameters = 0,
        )
    }
}
