package com.agrotech.oviapp.weights

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong

class SqlDelightWeightStore(database: OviAppDatabase) : WeightStore {
    private val queries = database.oviAppDatabaseQueries

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

    override fun insert(animal: AnimalForWeighing, id: String, weight: ValidWeight, nowIso: String) {
        queries.insertWeightMeasurement(
            id,
            animal.ranchId,
            animal.id,
            weight.milestone.name,
            weight.weightHundredths / 100.0,
            weight.scrotalHundredths?.let { it / 100.0 },
            weight.date.toString(),
            weight.notes,
            nowIso,
            nowIso,
        )
    }

    // Las fechas se guardan como ISO; se toma solo yyyy-MM-dd por si alguna trae hora.
    private fun String.toLocalDate(): LocalDate = LocalDate.parse(take(10))
}
