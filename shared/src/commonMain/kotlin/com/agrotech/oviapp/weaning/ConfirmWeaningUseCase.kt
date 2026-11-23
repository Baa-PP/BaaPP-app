package com.agrotech.oviapp.weaning

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import com.agrotech.oviapp.sync.UuidV7
import com.agrotech.oviapp.sync.currentTimeMillis
import com.agrotech.oviapp.weights.AnimalForWeighing
import com.agrotech.oviapp.weights.Decimals
import com.agrotech.oviapp.weights.WeightError
import com.agrotech.oviapp.weights.WeightInput
import com.agrotech.oviapp.weights.WeightMilestone
import com.agrotech.oviapp.weights.WeightRules
import com.agrotech.oviapp.weights.WeightValidation
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/** Mismos destinos que `WeaningDestination` de BE-009. */
enum class WeaningDestination {
    LAMB_FATTENING,
    EWE_REPLACEMENT,
    SOLD,
    ;

    companion object {
        /** Un macho no puede ir a recría de hembra (EWE_REPLACEMENT), igual que `WeightPolicy`. */
        fun optionsFor(sex: Sex): List<WeaningDestination> =
            if (sex == Sex.MALE) entries.filter { it != EWE_REPLACEMENT } else entries
    }
}

/** Mismos códigos que el backend. */
enum class WeaningError { ANIMAL_ALREADY_WEANED, ANIMAL_NOT_ACTIVE, INVALID_WEANING_DESTINATION }

data class ConfirmWeaningInput(
    val weaningDate: LocalDate,
    val weaningWeightKg: String,
    val destination: WeaningDestination,
    val scrotalCircumferenceCm: String? = null,
)

/** Pesaje WEANING ya capturado (APP-15) que confirmar destete va a reutilizar, como el backend. */
data class ExistingWeaningWeight(val weightId: String, val weightKg: String, val date: LocalDate)

sealed interface ConfirmWeaningResult {
    data class Confirmed(val weightId: String, val motherTransitionedToEmpty: Boolean) : ConfirmWeaningResult
    data class Rejected(
        val weaningErrors: List<WeaningError>,
        val weightErrors: List<WeightError>,
    ) : ConfirmWeaningResult
    data object AnimalNotFound : ConfirmWeaningResult
}

/** Cuerpo de `POST /api/v1/animals/{id}/wean` + el animal; así BE-011 puede reproducir el destete tal cual. */
@Serializable
data class WeaningSyncPayload(
    val animalId: String,
    val weightId: String,
    val weaningDate: String,
    val weaningWeightKg: Double,
    val destination: WeaningDestination,
    val scrotalCircumferenceCm: Double?,
)

/**
 * Confirmar destete sin conexión (APP-18), espejo de `ConfirmWeaningUseCase` de BE-009: en una sola
 * transacción de SQLite guarda el pesaje WEANING, actualiza la cría según el destino, pasa a la madre
 * de LACTATING a EMPTY y encola un evento WEANING en `pending_sync_events`.
 */
class ConfirmWeaningUseCase(
    private val database: OviAppDatabase,
    private val queue: PendingSyncQueue,
    private val newId: () -> String = { UuidV7.generate(currentTimeMillis()) },
    private val now: () -> String = { Clock.System.now().toString() },
) {
    private val queries get() = database.oviAppDatabaseQueries
    private val json = Json { encodeDefaults = true }

    /** Para que el formulario muestre, antes de confirmar, el pesaje que se va a usar en lugar del tecleado. */
    fun existingWeaningWeight(animalId: String): ExistingWeaningWeight? =
        queries.selectWeaningWeightForAnimal(animalId).executeAsOneOrNull()?.let {
            ExistingWeaningWeight(
                weightId = it.id,
                weightKg = Decimals.format((it.weight_kg * 100).roundToLong(), scale = 2),
                date = LocalDate.parse(it.measurement_date.take(10)),
            )
        }

    fun confirm(animalId: String, input: ConfirmWeaningInput): ConfirmWeaningResult {
        val lamb = queries.selectLambForWeaning(animalId).executeAsOneOrNull()
            ?: return ConfirmWeaningResult.AnimalNotFound
        val sex = Sex.valueOf(lamb.sex)

        val weaningErrors = when {
            lamb.weaning_date != null -> listOf(WeaningError.ANIMAL_ALREADY_WEANED)
            lamb.status != AnimalStatus.ACTIVE.name || lamb.mortality_date != null -> listOf(WeaningError.ANIMAL_NOT_ACTIVE)
            input.destination !in WeaningDestination.optionsFor(sex) -> listOf(WeaningError.INVALID_WEANING_DESTINATION)
            else -> emptyList()
        }

        // Como el backend: si ya se capturó un pesaje WEANING (APP-15), se usa ese y su fecha.
        val existing = queries.selectWeaningWeightForAnimal(animalId).executeAsOneOrNull()
        val weaningDate = existing?.measurement_date?.let { LocalDate.parse(it.take(10)) } ?: input.weaningDate
        val weightValidation = WeightRules.validate(
            AnimalForWeighing(
                id = lamb.id,
                ranchId = lamb.ranch_id,
                sex = sex,
                birthDate = LocalDate.parse(lamb.birth_date.take(10)),
                deceased = false,
            ),
            WeightInput(WeightMilestone.WEANING, input.weaningWeightKg, input.scrotalCircumferenceCm, weaningDate),
        )
        val weightErrors = (weightValidation as? WeightValidation.Invalid)?.errors.orEmpty()
        if (weaningErrors.isNotEmpty() || weightErrors.isNotEmpty()) {
            return ConfirmWeaningResult.Rejected(weaningErrors, weightErrors)
        }
        val valid = (weightValidation as WeightValidation.Valid).weight

        val weightId = existing?.id ?: newId()
        val weightHundredths = existing?.let { (it.weight_kg * 100).roundToLong() } ?: valid.weightHundredths
        val scrotalHundredths = if (existing != null) {
            existing.scrotal_circumference_cm?.let { (it * 100).roundToLong() }
        } else {
            valid.scrotalHundredths
        }
        val (stage, status) = when (input.destination) {
            WeaningDestination.LAMB_FATTENING -> LifeStage.LAMB_FATTENING.name to AnimalStatus.ACTIVE.name
            WeaningDestination.EWE_REPLACEMENT -> LifeStage.EWE_REPLACEMENT.name to AnimalStatus.ACTIVE.name
            WeaningDestination.SOLD -> lamb.life_stage to AnimalStatus.SOLD.name
        }
        val payload = WeaningSyncPayload(
            animalId = lamb.id,
            weightId = weightId,
            weaningDate = weaningDate.toString(),
            weaningWeightKg = weightHundredths / 100.0,
            destination = input.destination,
            scrotalCircumferenceCm = scrotalHundredths?.let { it / 100.0 },
        )

        val timestamp = now()
        var motherTransitioned = false
        val mutation = queue.writeAndEnqueueIfChanged(SyncEntityType.WEANING, SyncOperation.CREATE, json.encodeToString(payload)) {
            // Primero la cría, con guarda: si otra escritura ya la desteteó, no se guarda ni se encola nada.
            queries.updateAnimalWeaning(weaningDate.toString(), stage, status, timestamp, lamb.id)
            val lambRows = queue.changedRows()
            if (lambRows == 0L) return@writeAndEnqueueIfChanged 0L
            if (existing == null) {
                queries.insertWeightMeasurement(
                    weightId,
                    lamb.ranch_id,
                    lamb.id,
                    WeightMilestone.WEANING.name,
                    weightHundredths / 100.0,
                    scrotalHundredths?.let { it / 100.0 },
                    weaningDate.toString(),
                    null,
                    timestamp,
                    timestamp,
                )
            }
            lamb.dam_id?.let { damId ->
                val damStatus = queries.selectReproductiveStatus(damId, lamb.ranch_id).executeAsOneOrNull()
                if (damStatus == ReproductiveStatus.LACTATING.name) {
                    queries.markMotherEmptyAfterWeaning(timestamp, damId, lamb.ranch_id)
                    motherTransitioned = true
                }
            }
            lambRows
        }
        if (mutation == null) {
            return ConfirmWeaningResult.Rejected(listOf(WeaningError.ANIMAL_ALREADY_WEANED), emptyList())
        }
        return ConfirmWeaningResult.Confirmed(weightId = weightId, motherTransitionedToEmpty = motherTransitioned)
    }
}
