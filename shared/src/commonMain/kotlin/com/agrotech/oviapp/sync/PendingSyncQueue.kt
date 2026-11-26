package com.agrotech.oviapp.sync

import com.agrotech.oviapp.db.OviAppDatabase

enum class SyncEntityType { ANIMAL, BIRTH_EVENT, WEIGHT_MEASUREMENT, WEANING }

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
    private val currentAccountId: () -> String? = { null },
) {
    private val queries get() = database.oviAppDatabaseQueries

    fun writeAndEnqueue(
        entityType: SyncEntityType,
        operation: SyncOperation,
        payloadJson: String,
        localPhotoPath: String? = null,
        accountId: String? = currentAccountId(),
        write: () -> Unit,
    ): PendingMutation = database.transactionWithResult {
        write()
        enqueue(entityType, operation, payloadJson, localPhotoPath, accountId)
    }

    /**
     * Como [writeAndEnqueue], pero [write] devuelve cuántas filas cambió y la mutación solo se encola si
     * cambió alguna. Sirve para escrituras con guarda (p. ej. `WHERE status = 'ACTIVE'`): si otra escritura
     * ganó la carrera, no se manda al servidor un payload viejo que la pisaría. Devuelve null si no cambió nada.
     */
    fun writeAndEnqueueIfChanged(
        entityType: SyncEntityType,
        operation: SyncOperation,
        payloadJson: String,
        localPhotoPath: String? = null,
        accountId: String? = currentAccountId(),
        write: () -> Long,
    ): PendingMutation? = database.transactionWithResult {
        if (write() == 0L) null else enqueue(entityType, operation, payloadJson, localPhotoPath, accountId)
    }

    /**
     * Filas que cambió la última escritura en esta conexión; los UPDATE de SQLDelight no lo devuelven.
     * Se usa dentro del bloque de [writeAndEnqueueIfChanged], justo después del UPDATE con guarda.
     */
    fun changedRows(): Long = queries.changedRows().executeAsOne()

    private fun enqueue(
        entityType: SyncEntityType,
        operation: SyncOperation,
        payloadJson: String,
        localPhotoPath: String?,
        accountId: String?,
    ): PendingMutation {
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
            account_id = accountId?.takeIf { it.isNotBlank() },
        )
        return mutation
    }

    /** Mutaciones PENDING en orden FIFO: el orden en que se escribieron en este celular. */
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

    /** Mutaciones PENDING de la cuenta activa; filas sin dueño o de otra cuenta nunca se suben. */
    fun pendingForAccount(accountId: String?): List<PendingMutation> {
        val owner = accountId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return queries.selectPendingSyncEventsByAccount(owner).executeAsList().map {
            PendingMutation(
                mutationId = it.mutation_id,
                entityType = SyncEntityType.valueOf(it.entity_type),
                operation = SyncOperation.valueOf(it.operation),
                occurredAt = it.occurred_at,
                payloadJson = it.payload_json,
                localPhotoPath = it.local_photo_path,
            )
        }
    }

    fun pendingCount(): Long = queries.countPendingSyncEvents().executeAsOne()

    fun pendingCountForAccount(accountId: String?): Long {
        val owner = accountId?.takeIf { it.isNotBlank() } ?: return 0L
        return queries.countPendingSyncEventsByAccount(owner).executeAsOne()
    }

    fun unownedPendingCount(): Long =
        queries.countUnownedPendingSyncEvents().executeAsOne()

    /**
     * Acción explícita de recuperación para capturas previas a la migración (account_id NULL).
     * Nunca se llama automáticamente en login.
     */
    fun claimUnownedPendingForAccount(accountId: String): Long {
        val owner = accountId.trim()
        require(owner.isNotEmpty()) { "accountId no puede estar vacío" }
        return database.transactionWithResult {
            queries.claimUnownedPendingSyncEvents(owner)
            changedRows()
        }
    }

    fun markSynced(mutationIds: Collection<String>) {
        database.transaction {
            mutationIds.forEach { queries.markSyncEventCompleted(it) }
        }
    }
}
