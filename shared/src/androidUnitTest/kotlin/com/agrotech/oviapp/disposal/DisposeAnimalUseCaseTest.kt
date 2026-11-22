package com.agrotech.oviapp.disposal

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bajas por muerte o venta contra SQLite real en memoria: nunca se borra la fila. */
class DisposeAnimalUseCaseTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: OviAppDatabase
    private lateinit var queue: PendingSyncQueue
    private lateinit var useCase: DisposeAnimalUseCase
    private var nextId = 0

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        queue = PendingSyncQueue(database, nowMillis = { 1_795_000_000_000 }, newMutationId = { "m${++nextId}" })
        useCase = DisposeAnimalUseCase(database, queue, now = { "2026-11-22T12:00:00Z" })
        insertAnimal("dam", sex = "FEMALE", birth = "2023-01-10", stage = "ADULT")
        insertAnimal("lamb", sex = "FEMALE", damId = "dam")
        insertAnimal("ram-lamb", sex = "MALE", damId = "dam")
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun aDeathKeepsTheRowWithDateCauseAndStageAndQueuesTheUpdate() {
        val result = useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 11, 20), cause = "  Neumonía "))

        assertIs<DisposalResult.Done>(result)
        val row = animal("lamb")
        assertEquals("DECEASED", row.status)
        assertEquals("2026-11-20", row.mortality_date)
        assertEquals("Neumonía", row.mortality_cause)
        assertEquals("LAMB_LACTATING", row.life_stage_at_death)

        val event = queue.pending().single()
        assertEquals(SyncEntityType.ANIMAL, event.entityType)
        assertEquals(SyncOperation.UPDATE, event.operation)
        val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
        assertEquals("lamb", payload["animalId"]!!.jsonPrimitive.content)
        assertEquals("DECEASED", payload["status"]!!.jsonPrimitive.content)
        assertEquals("2026-11-20", payload["mortalityDate"]!!.jsonPrimitive.content)
        assertEquals("Neumonía", payload["mortalityCause"]!!.jsonPrimitive.content)
        assertEquals("LAMB_LACTATING", payload["lifeStageAtDeath"]!!.jsonPrimitive.content)
        // PUT /animals/{id} pide el animal completo: el payload lleva también los campos obligatorios.
        assertEquals("lamb-folio", payload["folioInterno"]!!.jsonPrimitive.content)
        assertEquals("FEMALE", payload["sex"]!!.jsonPrimitive.content)
        assertEquals("2026-09-08", payload["birthDate"]!!.jsonPrimitive.content)
        assertEquals("LAMB_LACTATING", payload["lifeStage"]!!.jsonPrimitive.content)
        assertEquals("EMPTY", payload["reproductiveStatus"]!!.jsonPrimitive.content)
        assertEquals("dam", payload["damId"]!!.jsonPrimitive.content)
    }

    @Test
    fun theDeceasedLeavesOperationalListsButStaysInTheMotherHistoryAndMortalitySummary() {
        useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 11, 20), cause = "Neumonía"))

        val operational = database.oviAppDatabaseQueries.selectAnimalsByRanchPaged("r1", 50, 0).executeAsList().map { it.id }
        assertEquals(listOf("dam", "ram-lamb"), operational.sorted())
        val lambs = database.oviAppDatabaseQueries.selectLambsPaged("r1", 50, 0).executeAsList().map { it.id }
        assertEquals(listOf("ram-lamb"), lambs)

        val motherHistory = database.oviAppDatabaseQueries.selectOffspringByParent("dam").executeAsList()
        assertEquals(setOf("lamb", "ram-lamb"), motherHistory.map { it.id }.toSet())
        assertEquals("DECEASED", motherHistory.single { it.id == "lamb" }.status)

        val summary = useCase.mortalitySummary("r1", from = LocalDate(2026, 1, 1), to = LocalDate(2026, 12, 31))
        assertEquals(listOf(MortalitySummaryRow("Neumonía", LifeStage.LAMB_LACTATING, 1)), summary)
    }

    @Test
    fun aSaleOnlyChangesTheStatusAndAlsoStaysInTheMotherHistory() {
        assertIs<DisposalResult.Done>(useCase.recordSale("ram-lamb"))

        val row = animal("ram-lamb")
        assertEquals("SOLD", row.status)
        assertNull(row.mortality_date)
        assertNull(row.life_stage_at_death)
        val payload = Json.parseToJsonElement(queue.pending().single().payloadJson).jsonObject
        assertEquals("SOLD", payload["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["mortalityDate"])

        assertTrue("ram-lamb" !in database.oviAppDatabaseQueries.selectLambsPaged("r1", 50, 0).executeAsList().map { it.id })
        assertTrue("ram-lamb" in database.oviAppDatabaseQueries.selectOffspringByParent("dam").executeAsList().map { it.id })
    }

    @Test
    fun usesTheGivenStageAtDeath() {
        useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 11, 20), "Depredador", LifeStage.LAMB_FATTENING))

        assertEquals("LAMB_FATTENING", animal("lamb").life_stage_at_death)
    }

    @Test
    fun rejectsLikeTheBackendAndChangesNothing() {
        assertEquals(
            listOf(DisposalError.MORTALITY_INFO_REQUIRED),
            errors(useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 11, 20), cause = "  "))),
        )
        assertEquals(
            listOf(DisposalError.INVALID_MORTALITY_DATE),
            errors(useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 9, 1), cause = "Frío"))),
        )
        assertEquals(
            listOf(DisposalError.INVALID_LIFE_STAGE),
            errors(useCase.recordDeath("ram-lamb", MortalityInput(LocalDate(2026, 11, 20), "Frío", LifeStage.EWE_REPLACEMENT))),
        )
        assertEquals("ACTIVE", animal("lamb").status)
        assertEquals("ACTIVE", animal("ram-lamb").status)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun anAnimalCanOnlyBeDisposedOnce() {
        useCase.recordSale("lamb")

        assertEquals(listOf(DisposalError.ANIMAL_NOT_ACTIVE), errors(useCase.recordSale("lamb")))
        assertEquals(
            listOf(DisposalError.ANIMAL_NOT_ACTIVE),
            errors(useCase.recordDeath("lamb", MortalityInput(LocalDate(2026, 11, 20), "Frío"))),
        )
        assertIs<DisposalResult.AnimalNotFound>(useCase.recordSale("ghost"))
        assertEquals(1, queue.pendingCount())
    }

    private fun errors(result: DisposalResult) = assertIs<DisposalResult.Rejected>(result).errors

    private fun animal(id: String) = database.oviAppDatabaseQueries.selectAnimalById(id).executeAsOne()

    private fun insertAnimal(
        id: String,
        sex: String,
        birth: String = "2026-09-08",
        damId: String? = null,
        stage: String = "LAMB_LACTATING",
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO local_animals (id, ranch_id, folio_interno, breed, color, sex, birth_date, dam_id,
                    life_stage, reproductive_status, status)
                VALUES (?, 'r1', ?, 'Dorper', 'Blanco', ?, ?, ?, ?, 'EMPTY', 'ACTIVE')
            """.trimIndent(),
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, "$id-folio")
            bindString(2, sex)
            bindString(3, birth)
            bindString(4, damId)
            bindString(5, stage)
        }
    }
}
