package com.agrotech.oviapp.disposal

import com.agrotech.oviapp.db.Local_animals
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Mismos códigos que `AnimalPolicy` del backend (BE-005). */
enum class DisposalError {
    MORTALITY_INFO_REQUIRED,
    INVALID_MORTALITY_DATE,
    INVALID_LIFE_STAGE,
    ANIMAL_NOT_ACTIVE,
}

/** [lifeStageAtDeath] nulo = la etapa que tenía el animal, como hace el backend. */
data class MortalityInput(
    val date: LocalDate,
    val cause: String,
    val lifeStageAtDeath: LifeStage? = null,
)

sealed interface DisposalResult {
    data object Done : DisposalResult
    data class Rejected(val errors: List<DisposalError>) : DisposalResult
    data object AnimalNotFound : DisposalResult
}

data class MortalitySummaryRow(val cause: String, val lifeStageAtDeath: LifeStage?, val count: Long)

/** Animal completo como lo pide `PUT /api/v1/animals/{id}` (UpdateAnimalRequest) + su id. */
@Serializable
data class AnimalUpdatePayload(
    val animalId: String,
    val folioInterno: String,
    val siniigaChip: String?,
    val registroUno: String?,
    val adn: String?,
    val breed: String,
    val color: String,
    val sex: String,
    val birthDate: String,
    val weaningDate: String?,
    val entryReason: String,
    val criador: String?,
    val parturitionType: String,
    val conceptionMethod: String,
    val sireId: String?,
    val damId: String?,
    val externalSireFolio: String?,
    val externalDamFolio: String?,
    val lifeStage: String,
    val reproductiveStatus: String,
    val status: String,
    val mortalityDate: String?,
    val mortalityCause: String?,
    val lifeStageAtDeath: String?,
    val photoS3Key: String?,
)

/**
 * Bajas sin borrado físico (APP-19): la muerte deja al animal en DECEASED con fecha, causa y etapa al
 * morir, y la venta en SOLD. La fila nunca se borra, así sigue en el historial de partos de la madre
 * y en el resumen de mortalidad; solo sale de las listas operativas, que filtran `status = 'ACTIVE'`.
 * Cada baja se guarda junto con su evento en `pending_sync_events`, en una sola transacción.
 */
class DisposeAnimalUseCase(
    private val database: OviAppDatabase,
    private val queue: PendingSyncQueue,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    private val queries get() = database.oviAppDatabaseQueries
    private val json = Json { encodeDefaults = true }

    fun recordDeath(animalId: String, input: MortalityInput): DisposalResult {
        val animal = queries.selectAnimalById(animalId).executeAsOneOrNull() ?: return DisposalResult.AnimalNotFound
        if (animal.status != AnimalStatus.ACTIVE.name) return DisposalResult.Rejected(listOf(DisposalError.ANIMAL_NOT_ACTIVE))

        val cause = input.cause.trim()
        val stageAtDeath = input.lifeStageAtDeath ?: LifeStage.valueOf(animal.life_stage)
        val errors = buildList {
            if (cause.isEmpty()) add(DisposalError.MORTALITY_INFO_REQUIRED)
            if (input.date < LocalDate.parse(animal.birth_date.take(10))) add(DisposalError.INVALID_MORTALITY_DATE)
            if (animal.sex == Sex.MALE.name && stageAtDeath == LifeStage.EWE_REPLACEMENT) add(DisposalError.INVALID_LIFE_STAGE)
        }
        if (errors.isNotEmpty()) return DisposalResult.Rejected(errors)

        val updated = animal.copy(
            status = AnimalStatus.DECEASED.name,
            mortality_date = input.date.toString(),
            mortality_cause = cause,
            life_stage_at_death = stageAtDeath.name,
        )
        save(updated) {
            queries.markAnimalDeceased(updated.mortality_date, cause, stageAtDeath.name, now(), animal.id)
        }
        return DisposalResult.Done
    }

    fun recordSale(animalId: String): DisposalResult {
        val animal = queries.selectAnimalById(animalId).executeAsOneOrNull() ?: return DisposalResult.AnimalNotFound
        if (animal.status != AnimalStatus.ACTIVE.name) return DisposalResult.Rejected(listOf(DisposalError.ANIMAL_NOT_ACTIVE))

        save(animal.copy(status = AnimalStatus.SOLD.name)) { queries.markAnimalSold(now(), animal.id) }
        return DisposalResult.Done
    }

    fun mortalitySummary(ranchId: String, from: LocalDate, to: LocalDate): List<MortalitySummaryRow> =
        queries.selectMortalitySummary(ranchId, from.toString(), to.toString()).executeAsList().map {
            MortalitySummaryRow(
                cause = it.mortality_cause ?: "",
                lifeStageAtDeath = it.life_stage_at_death?.let(LifeStage::valueOf),
                count = it.deaths,
            )
        }

    private fun save(updated: Local_animals, write: () -> Unit) {
        queue.writeAndEnqueue(
            entityType = SyncEntityType.ANIMAL,
            operation = SyncOperation.UPDATE,
            payloadJson = json.encodeToString(updated.toPayload()),
            write = write,
        )
    }

    private fun Local_animals.toPayload() = AnimalUpdatePayload(
        animalId = id,
        folioInterno = folio_interno,
        siniigaChip = siniiga_chip,
        registroUno = registro_uno,
        adn = adn,
        breed = breed,
        color = color,
        sex = sex,
        birthDate = birth_date.take(10),
        weaningDate = weaning_date?.take(10),
        entryReason = entry_reason,
        criador = criador_name,
        parturitionType = parturition_type,
        conceptionMethod = conception_method,
        sireId = sire_id,
        damId = dam_id,
        externalSireFolio = external_sire_folio,
        externalDamFolio = external_dam_folio,
        lifeStage = life_stage,
        reproductiveStatus = reproductive_status,
        status = status,
        mortalityDate = mortality_date?.take(10),
        mortalityCause = mortality_cause,
        lifeStageAtDeath = life_stage_at_death,
        photoS3Key = photo_s3_key,
    )
}
