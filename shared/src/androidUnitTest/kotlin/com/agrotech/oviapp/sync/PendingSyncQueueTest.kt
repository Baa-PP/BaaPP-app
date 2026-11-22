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
    fun readsPendingMutationsInChronologicalOrder() {
        nowMillis = 1_795_361_405_300L
        val third = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.UPDATE, "{}") {}
        nowMillis = 1_795_361_405_100L
        val first = queue.writeAndEnqueue(SyncEntityType.ANIMAL, SyncOperation.CREATE, "{}") {}
        nowMillis = 1_795_361_405_200L
        val second = queue.writeAndEnqueue(SyncEntityType.BIRTH_EVENT, SyncOperation.CREATE, "{}", localPhotoPath = "/fotos/parto.webp") {}

        val pending = queue.pending()

        assertEquals(listOf(first.mutationId, second.mutationId, third.mutationId), pending.map { it.mutationId })
        assertEquals("/fotos/parto.webp", pending[1].localPhotoPath)
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
