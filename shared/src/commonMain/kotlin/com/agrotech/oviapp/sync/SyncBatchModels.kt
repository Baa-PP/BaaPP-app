package com.agrotech.oviapp.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Cuerpo de `POST /api/v1/sync/batch` (BE-011).
 * Las mutaciones viajan en orden FIFO de captura para preservar dependencias causales
 * (por ejemplo: crear animal/parto antes de su pesaje o actualización).
 */
@Serializable
data class SyncBatchRequest(
    val mutations: List<SyncBatchMutationDto>,
)

@Serializable
data class SyncBatchMutationDto(
    @SerialName("mutation_id")
    val mutationId: String,
    @SerialName("entity_type")
    val entityType: String,
    val operation: String,
    @SerialName("occurred_at")
    val occurredAt: String,
    val payload: JsonElement,
)

@Serializable
data class SyncMutationResult(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: String,
    val error: String? = null,
) {
    /**
     * Tanto una mutación recién aplicada como un duplicado ya procesado en un intento previo
     * quedan en estado terminal [SyncStatus.SYNCED] en el teléfono para no reenviarlas.
     */
    fun isSyncedStatus(): Boolean = status.uppercase() in ACCEPTED_STATUSES

    companion object {
        private val ACCEPTED_STATUSES = setOf(
            "APPLIED",
            "SYNCED",
            "DUPLICATE",
            "ALREADY_PROCESSED",
            "OK",
        )
    }
}

@Serializable
data class SyncBatchResponse(
    val results: List<SyncMutationResult> = emptyList(),
    @SerialName("synced_mutation_ids")
    val syncedMutationIds: List<String> = emptyList(),
) {
    fun resolvedSyncedIds(sentIds: Set<String>): Set<String> {
        if (results.isNotEmpty()) {
            return results
                .asSequence()
                .filter { it.mutationId in sentIds && it.isSyncedStatus() }
                .map { it.mutationId }
                .toSet()
        }
        return syncedMutationIds.filterTo(mutableSetOf()) { it in sentIds }
    }

    fun failedResults(sentIds: Set<String>): List<SyncMutationResult> =
        results.filter { it.mutationId in sentIds && !it.isSyncedStatus() }
}
