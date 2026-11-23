package com.agrotech.oviapp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSyncQueueTest {

    private lateinit var database: OviAppDatabase
    private var nowMillis = 1_795_361_405_000L
    private lateinit var queue: PendingSyncQueue

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        database = OviAppDatabase(driver)
        queue = PendingSyncQueue(database, nowMillis = { nowMillis })
    }

    @Test
    fun writesTheDataAndEnqueuesTheMutationInTheSameTransaction() {
        val mutation = queue.writeAndEnqueue(
            entityType = SyncEntityType.WEIGHT_MEASUREMENT,
            operation = SyncOperation.CREATE,
            payloadJson = """{"id":"w1"}""",
        ) { insertWeight("w1") }

        assertEquals("w1", weightIds().single())
        val pending = queue.pending().single()
        assertEquals(mutation.mutationId, pending.mutationId)
        assertEquals(SyncEntityType.WEIGHT_MEASUREMENT, pending.entityType)
        assertEquals(SyncOperation.CREATE, pending.operation)
        assertEquals("2026-11-22T15:30:05.000Z", pending.occurredAt)
        assertEquals("""{"id":"w1"}""", pending.payloadJson)
        assertNull(pending.localPhotoPath)
    }

    @Test
    fun aFailedLocalWriteDoesNotLeaveAnOrphanMutation() {
        assertFailsWith<IllegalStateException> {
            queue.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, "{}") {
                insertWeight("w1")
                error("Falla a la mitad de la captura")
            }
        }

        assertTrue(weightIds().isEmpty(), "El dato se revierte")
        assertTrue(queue.pending().isEmpty(), "No queda mutación sin dato")
    }

    @Test
    fun aFailedEnqueueRollsBackTheLocalWrite() {
        val repeatedId = "0193f3a2-0000-7000-8000-000000000001"
        val queueWithFixedId = PendingSyncQueue(database, nowMillis = { nowMillis }, newMutationId = { repeatedId })
        queueWithFixedId.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, "{}") { insertWeight("w1") }

        assertFailsWith<Exception> {
            queueWithFixedId.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, "{}") {
                insertWeight("w2")
            }
        }

        assertEquals(listOf("w1"), weightIds(), "w2 se revierte porque su mutación no se pudo encolar")
        assertEquals(1L, queue.pendingCount())
    }

    @Test
    fun readsPendingMutationsInWriteOrderEvenIfTheClockGoesBack() {
        // FIFO real: si el reloj del celular retrocede, el orden de escritura sigue siendo el causal.
        nowMillis = 1_795_361_405_300L
        val first = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        nowMillis = 1_795_361_405_100L
        val second = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {}
        nowMillis = 1_795_361_405_200L
        val third = queue.writeAndEnqueue(SyncEntityType.BIRTH_EVENT, SyncOperation.CREATE, "{}", localPhotoPath = "/fotos/parto.webp") {}

        val pending = queue.pending()

        assertEquals(listOf(first.mutationId, second.mutationId, third.mutationId), pending.map { it.mutationId })
        assertEquals("/fotos/parto.webp", pending[2].localPhotoPath)
    }

    @Test
    fun mutationsInTheSameMillisecondKeepTheirWriteOrder() {
        // Los ids van al revés del orden de escritura: antes el desempate por mutation_id los invertía.
        val ids = ArrayDeque(listOf("ffff-create", "0000-update"))
        val sameClock = PendingSyncQueue(database, nowMillis = { nowMillis }, newMutationId = { ids.removeFirst() })

        sameClock.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        sameClock.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {}

        assertEquals(listOf("ffff-create", "0000-update"), queue.pending().map { it.mutationId })
    }

    @Test
    fun anUnchangedWriteDoesNotEnqueueAnything() {
        val skipped = queue.writeAndEnqueueIfChanged(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") { 0L }
        val queued = queue.writeAndEnqueueIfChanged(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") { 1L }

        assertNull(skipped)
        assertEquals(listOf(queued!!.mutationId), queue.pending().map { it.mutationId })
    }

    @Test
    fun changedRowsReportsWhatTheLastGuardedUpdateDid() {
        insertWeight("w1")
        val queries = database.oviAppDatabaseQueries

        val hit = queue.writeAndEnqueueIfChanged(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {
            queries.markSyncEventCompleted("no-existe")
            queue.changedRows()
        }

        assertNull(hit, "un UPDATE que no encontró fila no encola nada")
        assertEquals(0L, queue.pendingCount())

        val existing = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        val updated = queue.writeAndEnqueueIfChanged(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {
            queries.markSyncEventCompleted(existing.mutationId)
            queue.changedRows()
        }
        assertEquals(listOf(updated!!.mutationId), queue.pending().map { it.mutationId })
    }

    @Test
    fun syncedMutationsLeaveThePendingQueue() {
        val a = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        nowMillis += 1
        val b = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {}

        queue.markSynced(listOf(a.mutationId))

        assertEquals(listOf(b.mutationId), queue.pending().map { it.mutationId })
        assertEquals(1L, queue.pendingCount())
    }

    @Test
    fun theMigrationKeepsAlreadyQueuedMutationsInInsertionOrder() {
        val oldDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Cola tal como quedó en la versión 2, con dos mutaciones cuyo id va al revés de su inserción.
        oldDriver.execute(
            null,
            """
            CREATE TABLE pending_sync_events (
                mutation_id TEXT NOT NULL PRIMARY KEY, entity_type TEXT NOT NULL, operation TEXT NOT NULL,
                occurred_at TEXT NOT NULL, payload_json TEXT NOT NULL, local_photo_path TEXT,
                sync_status TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent(),
            0,
        )
        oldDriver.execute(null, "INSERT INTO pending_sync_events VALUES ('zz', 'ANIMAL', 'CREATE', 't', '{}', NULL, 'PENDING')", 0)
        oldDriver.execute(null, "INSERT INTO pending_sync_events VALUES ('aa', 'ANIMAL', 'UPDATE', 't', '{}', NULL, 'PENDING')", 0)

        OviAppDatabase.Schema.migrate(oldDriver, oldVersion = 2, newVersion = 3)
        val migrated = PendingSyncQueue(OviAppDatabase(oldDriver), nowMillis = { nowMillis }, newMutationId = { "new" })
        migrated.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {}

        assertEquals(listOf("zz", "aa", "new"), migrated.pending().map { it.mutationId })
        oldDriver.close()
    }

    @Test
    fun theNextSequenceIsFoundThroughAnIndexNotAFullScan() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        val plan = driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN SELECT COALESCE(MAX(sequence), 0) + 1 FROM pending_sync_events",
            mapper = { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.next().value) rows += cursor.getString(3).orEmpty()
                app.cash.sqldelight.db.QueryResult.Value(rows.joinToString(" | "))
            },
            parameters = 0,
        ).value

        assertTrue("idx_pending_sync_events_sequence" in plan, "Plan de consulta: $plan")
        driver.close()
    }

    private fun insertWeight(id: String) {
        database.oviAppDatabaseQueries.insertWeightMeasurement(
            id = id,
            ranch_id = "r1",
            animal_id = "a1",
            milestone = "BIRTH",
            weight_kg = 4.1,
            scrotal_circumference_cm = null,
            measurement_date = "2026-11-22",
            notes = null,
            created_at = "2026-11-22T15:30:05.000Z",
            updated_at = "2026-11-22T15:30:05.000Z",
        )
    }

    private fun weightIds(): List<String> =
        database.oviAppDatabaseQueries.selectWeightsByAnimalPaged("a1", 100, 0).executeAsList().map { it.id }
}
