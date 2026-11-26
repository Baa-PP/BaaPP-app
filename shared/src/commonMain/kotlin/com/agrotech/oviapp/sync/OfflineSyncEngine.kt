package com.agrotech.oviapp.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

sealed interface SyncOutcome {
    data object NothingToSync : SyncOutcome
    data object NotAuthenticated : SyncOutcome
    data class Success(val syncedCount: Int, val failedCount: Int = 0) : SyncOutcome
    data class RetryLater(val reason: String, val cause: Throwable? = null) : SyncOutcome
}

/**
 * Motor de sincronización offline-first (APP-21).
 * Lee las mutaciones `PENDING` de [PendingSyncQueue] en orden FIFO, las envía por lotes a
 * `POST /api/v1/sync/batch` y marca `SYNCED` únicamente los `mutation_id` confirmados por el servidor.
 */
class OfflineSyncEngine internal constructor(
    private val queue: PendingSyncQueue,
    private val api: SyncBatchApi,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val json: Json = DefaultJson,
    private val pendingProvider: () -> List<PendingMutation> = { queue.pending() },
) {
    constructor(
        queue: PendingSyncQueue,
        api: SyncBatchApi,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        pendingProvider: () -> List<PendingMutation> = { queue.pending() },
    ) : this(queue, api, batchSize, DefaultJson, pendingProvider)

    init {
        require(batchSize > 0) { "batchSize debe ser mayor a 0" }
    }

    private val syncMutex = Mutex()

    suspend fun syncPending(isAuthenticated: () -> Boolean = { true }): SyncOutcome =
        syncMutex.withLock {
            if (!isAuthenticated()) {
                return@withLock SyncOutcome.NotAuthenticated
            }

            val pending = pendingProvider()
            if (pending.isEmpty()) {
                return@withLock SyncOutcome.NothingToSync
            }

            var totalSynced = 0
            var totalFailed = 0

            for (chunk in pending.chunked(batchSize)) {
                val dtoList = chunk.map { mutation ->
                    SyncBatchMutationDto(
                        mutationId = mutation.mutationId,
                        entityType = mutation.entityType.name,
                        operation = mutation.operation.name,
                        occurredAt = mutation.occurredAt,
                        payload = parsePayload(mutation.payloadJson),
                    )
                }
                val sentIds = chunk.mapTo(mutableSetOf()) { it.mutationId }

                val response = try {
                    api.sendBatch(SyncBatchRequest(mutations = dtoList))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SyncBatchAuthException) {
                    return@withLock SyncOutcome.NotAuthenticated
                } catch (e: Exception) {
                    return@withLock SyncOutcome.RetryLater(
                        reason = e.message ?: "No se pudo enviar el lote de sincronización.",
                        cause = e,
                    )
                }

                val syncedIds = response.resolvedSyncedIds(sentIds)
                if (syncedIds.isNotEmpty()) {
                    queue.markSynced(syncedIds)
                    totalSynced += syncedIds.size
                }

                val failed = response.failedResults(sentIds)
                totalFailed += failed.size

                // Si cualquier mutación del lote quedó sin confirmar, conservamos las ya marcadas
                // y devolvemos RetryLater antes de avanzar a lotes posteriores.
                if (syncedIds.size < sentIds.size) {
                    val detail = failed.firstOrNull()?.error
                        ?: "El servidor no confirmó todas las mutaciones del lote."
                    return@withLock SyncOutcome.RetryLater(reason = detail)
                }
            }

            SyncOutcome.Success(syncedCount = totalSynced, failedCount = totalFailed)
        }

    private fun parsePayload(payloadJson: String) =
        try {
            json.parseToJsonElement(payloadJson)
        } catch (_: Exception) {
            JsonPrimitive(payloadJson)
        }

    companion object {
        const val DEFAULT_BATCH_SIZE = 50
        private val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
