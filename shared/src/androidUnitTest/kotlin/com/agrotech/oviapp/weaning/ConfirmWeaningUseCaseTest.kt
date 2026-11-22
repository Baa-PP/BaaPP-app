package com.agrotech.oviapp.weaning

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import com.agrotech.oviapp.weights.WeightError
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Confirmar destete contra SQLite real en memoria: todo local y en una sola transacción. */
class ConfirmWeaningUseCaseTest {
    private val weaningDay = LocalDate(2026, 11, 22)
    private lateinit var driver: SqlDriver
    private lateinit var database: OviAppDatabase
    private lateinit var queue: PendingSyncQueue
    private lateinit var useCase: ConfirmWeaningUseCase
    private var nextId = 0

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        queue = PendingSyncQueue(database, nowMillis = { 1_795_000_000_000 }, newMutationId = { "m${++nextId}" })
        useCase = ConfirmWeaningUseCase(database, queue, newId = { "w${++nextId}" }, now = { "2026-11-22T12:00:00Z" })
        insertAnimal("dam", sex = "FEMALE", birth = "2023-01-10", stage = "ADULT", reproductive = "LACTATING")
        insertAnimal("lamb", sex = "FEMALE", damId = "dam")
        insertAnimal("ram-lamb", sex = "MALE", damId = "dam")
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun theMotherBecomesEmptyTheWeightIsSavedAndTheEventIsQueued() {
        val result = useCase.confirm("lamb", input(weight = "19.50", destination = WeaningDestination.EWE_REPLACEMENT))

        val confirmed = assertIs<ConfirmWeaningResult.Confirmed>(result)
        assertTrue(confirmed.motherTransitionedToEmpty)

        assertEquals("EMPTY", animal("dam").reproductive_status)

        val weight = database.oviAppDatabaseQueries.selectLatestWeightByAnimal("lamb").executeAsOne()
        assertEquals("WEANING", weight.milestone)
        assertEquals(19.5, weight.weight_kg)
        assertEquals("2026-11-22", weight.measurement_date)

        val lamb = animal("lamb")
        assertEquals("2026-11-22", lamb.weaning_date)
        assertEquals("EWE_REPLACEMENT", lamb.life_stage)
        assertEquals("ACTIVE", lamb.status)

        val event = queue.pending().single()
        assertEquals(SyncEntityType.WEANING, event.entityType)
        assertEquals(SyncOperation.CREATE, event.operation)
        val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
        assertEquals("lamb", payload["animalId"]!!.jsonPrimitive.content)
        assertEquals(confirmed.weightId, payload["weightId"]!!.jsonPrimitive.content)
        assertEquals("2026-11-22", payload["weaningDate"]!!.jsonPrimitive.content)
        assertEquals("19.5", payload["weaningWeightKg"]!!.jsonPrimitive.content)
        assertEquals("EWE_REPLACEMENT", payload["destination"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["scrotalCircumferenceCm"])
    }

    @Test
    fun destinationDecidesTheLambStageOrSale() {
        useCase.confirm("ram-lamb", input(destination = WeaningDestination.LAMB_FATTENING, scrotal = "18"))
        assertEquals("LAMB_FATTENING", animal("ram-lamb").life_stage)

        useCase.confirm("lamb", input(destination = WeaningDestination.SOLD))
        assertEquals("SOLD", animal("lamb").status)
        assertEquals("LAMB_LACTATING", animal("lamb").life_stage)
    }

    @Test
    fun aMaleCannotGoToEweReplacementAndNothingChanges() {
        val result = useCase.confirm("ram-lamb", input(destination = WeaningDestination.EWE_REPLACEMENT))

        assertEquals(listOf(WeaningError.INVALID_WEANING_DESTINATION), assertIs<ConfirmWeaningResult.Rejected>(result).weaningErrors)
        assertNothingChanged("ram-lamb")
    }

    @Test
    fun invalidWeightsAreRejectedWithTheWeighingRules() {
        val result = useCase.confirm("lamb", input(weight = "0", scrotal = "10"))

        assertEquals(
            listOf(WeightError.INVALID_WEIGHT_KG, WeightError.SCROTAL_FEMALE_NOT_ALLOWED),
            assertIs<ConfirmWeaningResult.Rejected>(result).weightErrors,
        )
        assertNothingChanged("lamb")
    }

    @Test
    fun cannotWeanTwiceOrAnInactiveAnimal() {
        useCase.confirm("lamb", input())
        val second = useCase.confirm("lamb", input())
        assertEquals(listOf(WeaningError.ANIMAL_ALREADY_WEANED), assertIs<ConfirmWeaningResult.Rejected>(second).weaningErrors)

        insertAnimal("sold", sex = "FEMALE", status = "SOLD")
        val sold = useCase.confirm("sold", input())
        assertEquals(listOf(WeaningError.ANIMAL_NOT_ACTIVE), assertIs<ConfirmWeaningResult.Rejected>(sold).weaningErrors)

        assertIs<ConfirmWeaningResult.AnimalNotFound>(useCase.confirm("ghost", input()))
        assertEquals(1, queue.pendingCount())
    }

    @Test
    fun aMotherThatIsNotLactatingKeepsHerStatus() {
        driver.execute(null, "UPDATE local_animals SET reproductive_status = 'PREGNANT' WHERE id = 'dam'", 0)

        val result = useCase.confirm("lamb", input())

        assertFalse(assertIs<ConfirmWeaningResult.Confirmed>(result).motherTransitionedToEmpty)
        assertEquals("PREGNANT", animal("dam").reproductive_status)
    }

    @Test
    fun reusesAWeaningWeightAlreadyCapturedLikeTheBackend() {
        driver.execute(
            null,
            """
            INSERT INTO local_weight_measurements (id, ranch_id, animal_id, milestone, weight_kg, measurement_date)
            VALUES ('prev', 'r1', 'lamb', 'WEANING', 18.0, '2026-11-20')
            """.trimIndent(),
            0,
        )

        val result = useCase.confirm("lamb", input(weight = "25"))

        assertEquals("prev", assertIs<ConfirmWeaningResult.Confirmed>(result).weightId)
        assertEquals("2026-11-20", animal("lamb").weaning_date)
        assertEquals(1, database.oviAppDatabaseQueries.selectWeightsByAnimalChronological("lamb").executeAsList().size)
        val payload = Json.parseToJsonElement(queue.pending().single().payloadJson).jsonObject
        assertEquals("prev", payload["weightId"]!!.jsonPrimitive.content)
        assertEquals("18.0", payload["weaningWeightKg"]!!.jsonPrimitive.content)
    }

    private fun assertNothingChanged(lambId: String) {
        assertNull(animal(lambId).weaning_date)
        assertEquals("LACTATING", animal("dam").reproductive_status)
        assertNull(database.oviAppDatabaseQueries.selectLatestWeightByAnimal(lambId).executeAsOneOrNull())
        assertEquals(0, queue.pendingCount())
    }

    private fun animal(id: String) = database.oviAppDatabaseQueries.selectAnimalById(id).executeAsOne()

    private fun input(
        weight: String = "19",
        destination: WeaningDestination = WeaningDestination.LAMB_FATTENING,
        scrotal: String? = null,
    ) = ConfirmWeaningInput(
        weaningDate = weaningDay,
        weaningWeightKg = weight,
        destination = destination,
        scrotalCircumferenceCm = scrotal,
    )

    private fun insertAnimal(
        id: String,
        sex: String,
        birth: String = "2026-09-08",
        damId: String? = null,
        stage: String = "LAMB_LACTATING",
        reproductive: String = "EMPTY",
        status: String = "ACTIVE",
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO local_animals (id, ranch_id, folio_interno, breed, color, sex, birth_date, dam_id,
                    life_stage, reproductive_status, status)
                VALUES (?, 'r1', ?, 'Dorper', 'Blanco', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 8,
        ) {
            bindString(0, id)
            bindString(1, "$id-folio")
            bindString(2, sex)
            bindString(3, birth)
            bindString(4, damId)
            bindString(5, stage)
            bindString(6, reproductive)
            bindString(7, status)
        }
    }
}
