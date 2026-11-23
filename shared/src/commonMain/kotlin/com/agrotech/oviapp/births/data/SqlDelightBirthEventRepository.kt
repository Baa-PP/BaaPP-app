package com.agrotech.oviapp.births.data

import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import com.agrotech.oviapp.domain.model.currentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightBirthEventRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = ::currentEpochMillis
) : BirthEventRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveBirthEventOffline(
        draft: BirthEventDraft,
        todayDateIso: String
    ): BirthSaveResult = withContext(ioDispatcher) {
        val validationError = draft.validate(todayDateIso)
        if (validationError != null) {
            return@withContext BirthSaveResult.ValidationError(validationError)
        }

        val birthEventId = UuidV7.generate()
        val mutationId = UuidV7.generate()
        val occurredAt = formatEpochMillisToIsoUtc(clockMillis())
        val parturitionType = draft.deducedParturitionType()
        val cleanBirthDate = draft.birthDateIso.trim()

        try {
            database.transactionWithResult {
                val dam = database.oviAppDatabaseQueries
                    .selectAnimalById(draft.damId)
                    .executeAsOneOrNull()
                if (dam == null || dam.ranch_id != draft.ranchId || dam.sex != Sex.FEMALE.name) {
                    return@transactionWithResult BirthSaveResult.ValidationError(
                        "La madre seleccionada no es válida para este rancho."
                    )
                }

                if (!draft.sireId.isNullOrBlank()) {
                    val sire = database.oviAppDatabaseQueries
                        .selectAnimalById(draft.sireId)
                        .executeAsOneOrNull()
                    if (sire == null || sire.ranch_id != draft.ranchId || sire.sex != Sex.MALE.name) {
                        return@transactionWithResult BirthSaveResult.ValidationError(
                            "El semental seleccionado no es válido para este rancho."
                        )
                    }
                }

                val offspringPayloads = mutableListOf<SyncOffspringPayload>()
                val createdIds = mutableListOf<String>()

                for (child in draft.liveOffspring) {
                    val cleanFolio = child.folioInterno.trim()
                    val existing = database.oviAppDatabaseQueries
                        .selectAnimalByFolio(draft.ranchId, cleanFolio)
                        .executeAsOneOrNull()
                    if (existing != null) {
                        return@transactionWithResult BirthSaveResult.DuplicateFolio(
                            "Ya existe un ejemplar con el arete '$cleanFolio' en este rancho."
                        )
                    }

                    val childId = UuidV7.generate()
                    val weightId = UuidV7.generate()
                    val weightKg = child.birthWeightKg.trim().toDouble()

                    database.oviAppDatabaseQueries.insertAnimal(
                        id = childId,
                        ranch_id = draft.ranchId,
                        folio_interno = cleanFolio,
                        siniiga_chip = null,
                        registro_uno = null,
                        adn = null,
                        breed = dam.breed,
                        color = child.color.trim(),
                        sex = child.sex.name,
                        birth_date = cleanBirthDate,
                        weaning_date = null,
                        parturition_type = parturitionType.name,
                        conception_method = draft.conceptionMethod.name,
                        sire_id = draft.sireId,
                        dam_id = draft.damId,
                        external_sire_folio = null,
                        external_dam_folio = null,
                        entry_reason = EntryReason.BIRTH.name,
                        criador_name = null,
                        photo_s3_key = null,
                        birth_event_id = birthEventId,
                        mortality_date = null,
                        mortality_cause = null,
                        life_stage = LifeStage.LAMB_LACTATING.name,
                        reproductive_status = ReproductiveStatus.EMPTY.name,
                        status = AnimalStatus.ACTIVE.name,
                        updated_at = occurredAt
                    )

                    database.oviAppDatabaseQueries.insertWeightMeasurement(
                        id = weightId,
                        ranch_id = draft.ranchId,
                        animal_id = childId,
                        milestone = "BIRTH",
                        weight_kg = weightKg,
                        scrotal_circumference_cm = null,
                        measurement_date = cleanBirthDate,
                        notes = null,
                        created_at = occurredAt,
                        updated_at = occurredAt
                    )

                    createdIds.add(childId)
                    offspringPayloads.add(
                        SyncOffspringPayload(
                            id = childId,
                            folioInterno = cleanFolio,
                            sex = child.sex.name,
                            color = child.color.trim(),
                            birthWeightKg = weightKg
                        )
                    )
                }

                database.oviAppDatabaseQueries.insertBirthEvent(
                    id = birthEventId,
                    ranch_id = draft.ranchId,
                    dam_id = draft.damId,
                    sire_id = draft.sireId,
                    birth_date = cleanBirthDate,
                    live_births = draft.liveOffspring.size.toLong(),
                    stillborns = draft.stillborns.toLong(),
                    parturition_type = parturitionType.name,
                    conception_method = draft.conceptionMethod.name,
                    photo_s3_key = null,
                    local_photo_path = draft.localPhotoPath,
                    registered_by_user_id = draft.registeredByUserId,
                    notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                    created_at = occurredAt,
                    updated_at = occurredAt
                )

                val syncPayload = SyncBirthEventPayload(
                    birthEventId = birthEventId,
                    ranchId = draft.ranchId,
                    damId = draft.damId,
                    sireId = draft.sireId,
                    birthDate = cleanBirthDate,
                    liveBirths = draft.liveOffspring.size,
                    stillborns = draft.stillborns,
                    parturitionType = parturitionType.name,
                    conceptionMethod = draft.conceptionMethod.name,
                    offspring = offspringPayloads
                )

                database.oviAppDatabaseQueries.insertSyncEvent(
                    mutation_id = mutationId,
                    entity_type = "BIRTH_EVENT",
                    operation = "CREATE",
                    occurred_at = occurredAt,
                    payload_json = json.encodeToString(syncPayload),
                    local_photo_path = draft.localPhotoPath
                )

                BirthSaveResult.Success(
                    birthEventId = birthEventId,
                    offspringIds = createdIds,
                    mutationId = mutationId
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BirthSaveResult.Error(e.message ?: "Error guardando el evento de parto.")
        }
    }

    override suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>> =
        loadParentsBySex(ranchId, Sex.FEMALE)

    override suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>> =
        loadParentsBySex(ranchId, Sex.MALE)

    private suspend fun loadParentsBySex(ranchId: String, sex: Sex): List<Pair<String, String>> =
        withContext(ioDispatcher) {
            val pageSize = 200L
            var offset = 0L
            val all = mutableListOf<Pair<String, String>>()
            while (true) {
                val batch = database.oviAppDatabaseQueries
                    .selectAnimalsBySexPaged(ranchId, sex.name, pageSize, offset)
                    .executeAsList()
                    .map { it.id to it.folio_interno }
                all.addAll(batch)
                if (batch.size.toLong() < pageSize) break
                offset += pageSize
            }
            all
        }

    @Serializable
    private data class SyncOffspringPayload(
        val id: String,
        val folioInterno: String,
        val sex: String,
        val color: String,
        val birthWeightKg: Double
    )

    @Serializable
    private data class SyncBirthEventPayload(
        val birthEventId: String,
        val ranchId: String,
        val damId: String,
        val sireId: String?,
        val birthDate: String,
        val liveBirths: Int,
        val stillborns: Int,
        val parturitionType: String,
        val conceptionMethod: String,
        val offspring: List<SyncOffspringPayload>
    )
}

private fun formatEpochMillisToIsoUtc(epochMillis: Long): String {
    val totalSeconds = epochMillis.floorDiv(1000L)
    val second = totalSeconds.mod(60)
    val totalMinutes = totalSeconds.floorDiv(60L)
    val minute = totalMinutes.mod(60)
    val totalHours = totalMinutes.floorDiv(60L)
    val hour = totalHours.mod(24)
    val daysSinceEpoch = totalHours.floorDiv(24L)

    val z = daysSinceEpoch + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val month = (mp + (if (mp < 10L) 3L else -9L)).toInt()
    val year = (y + (if (month <= 2) 1L else 0L)).toInt()

    return "${year.toString().padStart(4, '0')}-" +
        "${month.toString().padStart(2, '0')}-" +
        "${day.toString().padStart(2, '0')}T" +
        "${hour.toString().padStart(2, '0')}:" +
        "${minute.toString().padStart(2, '0')}:" +
        "${second.toString().padStart(2, '0')}Z"
}
