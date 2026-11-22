package com.agrotech.oviapp.weights

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/** Cuerpo de `POST /api/v1/weights` (RegisterWeightRequest); el id local hace idempotente el reenvío. */
@Serializable
data class WeightSyncPayload(
    val id: String,
    val animalId: String,
    val milestone: WeightMilestone,
    val weightKg: Double,
    val scrotalCircumferenceCm: Double?,
    val date: String,
)

class SqlDelightWeightStore(
    database: OviAppDatabase,
    private val queue: PendingSyncQueue,
) : WeightStore {
    private val queries = database.oviAppDatabaseQueries
    private val json = Json { encodeDefaults = true }

    override fun animal(animalId: String): AnimalForWeighing? =
        queries.selectAnimalForWeighing(animalId).executeAsOneOrNull()?.let { row ->
            AnimalForWeighing(
                id = row.id,
                ranchId = row.ranch_id,
                sex = Sex.valueOf(row.sex),
                birthDate = row.birth_date.toLocalDate(),
                deceased = row.status == AnimalStatus.DECEASED.name || row.mortality_date != null,
            )
        }

    override fun weights(animalId: String): List<WeightRecord> =
        queries.selectWeightsByAnimalChronological(animalId).executeAsList().map { row ->
            WeightRecord(
                id = row.id,
                milestone = WeightMilestone.valueOf(row.milestone),
                date = row.measurement_date.toLocalDate(),
                weightHundredths = (row.weight_kg * 100).roundToLong(),
                scrotalHundredths = row.scrotal_circumference_cm?.let { (it * 100).roundToLong() },
            )
        }

    /** El pesaje y su evento WEIGHT_MEASUREMENT se guardan en la misma transacción (APP-20). */
    override fun insert(animal: AnimalForWeighing, id: String, weight: ValidWeight, nowIso: String) {
        val weightKg = weight.weightHundredths / 100.0
        val scrotalCm = weight.scrotalHundredths?.let { it / 100.0 }
        val payload = WeightSyncPayload(
            id = id,
            animalId = animal.id,
            milestone = weight.milestone,
            weightKg = weightKg,
            scrotalCircumferenceCm = scrotalCm,
            date = weight.date.toString(),
        )
        queue.writeAndEnqueue(SyncEntityType.WEIGHT_MEASUREMENT, SyncOperation.CREATE, json.encodeToString(payload)) {
            queries.insertWeightMeasurement(
                id,
                animal.ranchId,
                animal.id,
                weight.milestone.name,
                weightKg,
                scrotalCm,
                weight.date.toString(),
                weight.notes,
                nowIso,
                nowIso,
            )
        }
    }

    // Las fechas se guardan como ISO; se toma solo yyyy-MM-dd por si alguna trae hora.
    private fun String.toLocalDate(): LocalDate = LocalDate.parse(take(10))
}
