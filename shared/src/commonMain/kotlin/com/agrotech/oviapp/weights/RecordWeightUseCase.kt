package com.agrotech.oviapp.weights

import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface WeightStore {
    fun animal(animalId: String): AnimalForWeighing?
    fun weights(animalId: String): List<WeightRecord>
    fun insert(animal: AnimalForWeighing, id: String, weight: ValidWeight, nowIso: String)
}

sealed interface RecordWeightResult {
    data class Recorded(val entry: WeightGainEntry, val history: WeightHistory) : RecordWeightResult
    data class Rejected(val errors: List<WeightError>) : RecordWeightResult
    data object AnimalNotFound : RecordWeightResult
}

/**
 * Captura un pesaje en el corral: valida con las reglas de BE-009, lo guarda en SQLite y devuelve
 * su edad y GDP calculadas contra el pesaje anterior, todo sin conexión.
 */
@OptIn(ExperimentalUuidApi::class)
class RecordWeightUseCase(
    private val store: WeightStore,
    private val newId: () -> String = { Uuid.random().toString() },
    private val now: () -> String = { Clock.System.now().toString() },
) {
    fun record(animalId: String, input: WeightInput): RecordWeightResult {
        val animal = store.animal(animalId) ?: return RecordWeightResult.AnimalNotFound
        val weight = when (val validation = WeightRules.validate(animal, input)) {
            is WeightValidation.Invalid -> return RecordWeightResult.Rejected(validation.errors)
            is WeightValidation.Valid -> validation.weight
        }
        val id = newId()
        store.insert(animal, id, weight, now())

        val history = WeightGain.history(animal.birthDate, store.weights(animal.id))
        return RecordWeightResult.Recorded(entry = history.entries.first { it.record.id == id }, history = history)
    }

    fun history(animalId: String): WeightHistory? {
        val animal = store.animal(animalId) ?: return null
        return WeightGain.history(animal.birthDate, store.weights(animal.id))
    }
}
