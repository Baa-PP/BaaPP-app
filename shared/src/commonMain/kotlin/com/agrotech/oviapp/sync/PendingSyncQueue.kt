package com.agrotech.oviapp.sync

import com.agrotech.oviapp.db.OviAppDatabase

enum class SyncEntityType { ANIMAL, BIRTH_EVENT, WEIGHT_MEASUREMENT }

enum class SyncOperation { CREATE, UPDATE }

enum class SyncStatus { PENDING, SYNCED }

/** Cambio hecho en el celular que todavía tiene que llegar al servidor (POST /api/v1/sync/batch). */
data class PendingMutation(
    val mutationId: String,
    val entityType: SyncEntityType,
    val operation: SyncOperation,
    val occurredAt: String,
    val payloadJson: String,
    val localPhotoPath: String?,
)

/**
 * Cola de salida offline-first. Toda escritura local pasa por [writeAndEnqueue]: el dato y su mutación
 * se guardan en una sola transacción de SQLite, así nunca hay un dato sin sincronizar que el motor de
 * sincronización no conozca, ni una mutación que apunte a un dato que no se guardó.
 */
class PendingSyncQueue(
    private val database: OviAppDatabase,
    private val nowMillis: () -> Long = ::currentTimeMillis,
    private val newMutationId: () -> String = { UuidV7.generate(nowMillis()) },
) {
    private val queries get() = database.oviAppDatabaseQueries

    fun writeAndEnqueue(
        entityType: SyncEntityType,
        operation: SyncOperation,
        payloadJson: String,
        localPhotoPath: String? = null,
        write: () -> Unit,
    ): PendingMutation = database.transactionWithResult {
        write()
        val mutation = PendingMutation(
            mutationId = newMutationId(),
            entityType = entityType,
            operation = operation,
            occurredAt = IsoTimestamp.format(nowMillis()),
            payloadJson = payloadJson,
            localPhotoPath = localPhotoPath,
        )
        queries.insertSyncEvent(
            mutation_id = mutation.mutationId,
            entity_type = mutation.entityType.name,
            operation = mutation.operation.name,
            occurred_at = mutation.occurredAt,
            payload_json = mutation.payloadJson,
            local_photo_path = mutation.localPhotoPath,
        )
        mutation
    }

    /** Mutaciones PENDING en orden cronológico (y por id UUIDv7 si ocurrieron en el mismo milisegundo). */
    fun pending(): List<PendingMutation> =
        queries.selectPendingSyncEvents().executeAsList().map {
            PendingMutation(
                mutationId = it.mutation_id,
                entityType = SyncEntityType.valueOf(it.entity_type),
                operation = SyncOperation.valueOf(it.operation),
                occurredAt = it.occurred_at,
                payloadJson = it.payload_json,
                localPhotoPath = it.local_photo_path,
            )
        }

    fun pendingCount(): Long = queries.countPendingSyncEvents().executeAsOne()

    fun markSynced(mutationIds: Collection<String>) {
        database.transaction {
            mutationIds.forEach { queries.markSyncEventCompleted(it) }
        }
    }
}
