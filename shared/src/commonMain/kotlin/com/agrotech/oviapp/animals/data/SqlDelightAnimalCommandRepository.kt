package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.auth.domain.RanchAccessPolicy
import com.agrotech.oviapp.auth.domain.SessionStorage
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import com.agrotech.oviapp.domain.model.currentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightAnimalCommandRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = ::currentEpochMillis,
    private val sessionStorage: SessionStorage? = null
) : AnimalCommandRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveAnimalOffline(formState: AnimalFormState): AnimalSaveResult = withContext(ioDispatcher) {
        val validationError = formState.validate()
        if (validationError != null) {
            return@withContext AnimalSaveResult.ValidationError(validationError)
        }

        val isEdit = !formState.id.isNullOrBlank()
        if (isEdit && sessionStorage != null) {
            val activeSession = sessionStorage.getSession()
            val canEdit = activeSession?.let { RanchAccessPolicy.resolveCapabilities(it).canEditGeneralRecords } ?: false
            if (!canEdit) {
                return@withContext AnimalSaveResult.ValidationError(
                    "No tienes permiso para editar registros de ejemplares."
                )
            }
        }

        val targetFolio = formState.folioInterno.trim()
        val animalId = if (isEdit) formState.id!! else UuidV7.generate()
        val mutationId = UuidV7.generate()
        val occurredAt = formatEpochMillisToIsoUtc(clockMillis())
        val operation = if (isEdit) "UPDATE" else "CREATE"

        try {
            val existingByFolio = database.oviAppDatabaseQueries
                .selectAnimalByFolio(formState.ranchId, targetFolio)
                .executeAsOneOrNull()

            // Check duplicate folio locally
            if (existingByFolio != null && existingByFolio.id != animalId) {
                return@withContext AnimalSaveResult.DuplicateFolio(
                    "Ya existe un ejemplar con el arete '$targetFolio' en este rancho."
                )
            }

            val result = database.transactionWithResult<AnimalSaveResult> {
                if (isEdit) {
                    val existingAnimal = database.oviAppDatabaseQueries
                        .selectAnimalById(animalId)
                        .executeAsOneOrNull()
                    if (existingAnimal == null) {
                        return@transactionWithResult AnimalSaveResult.Error(
                            "No se encontró el ejemplar a editar."
                        )
                    }
                    database.oviAppDatabaseQueries.updateAnimalFormFields(
                        ranch_id = formState.ranchId,
                        folio_interno = targetFolio,
                        siniiga_chip = formState.siniigaChip?.trim()?.takeIf { it.isNotEmpty() },
                        registro_uno = formState.registroUno?.trim()?.takeIf { it.isNotEmpty() },
                        adn = formState.adn?.trim()?.takeIf { it.isNotEmpty() },
                        breed = formState.breed,
                        color = formState.color.trim(),
                        sex = formState.sex.name,
                        birth_date = formState.birthDate.trim(),
                        weaning_date = formState.weaningDate?.trim()?.takeIf { it.isNotEmpty() },
                        parturition_type = formState.parturitionType.name,
                        conception_method = formState.conceptionMethod.name,
                        sire_id = formState.sireId,
                        dam_id = formState.damId,
                        external_sire_folio = formState.externalSireFolio?.trim()?.takeIf { it.isNotEmpty() },
                        external_dam_folio = formState.externalDamFolio?.trim()?.takeIf { it.isNotEmpty() },
                        entry_reason = formState.entryReason.name,
                        criador_name = formState.criadorName?.trim()?.takeIf { it.isNotEmpty() },
                        photo_s3_key = formState.photoS3Key,
                        life_stage = formState.lifeStage.name,
                        reproductive_status = formState.reproductiveStatus.name,
                        updated_at = occurredAt,
                        id = animalId
                    )
                } else {
                    database.oviAppDatabaseQueries.insertAnimal(
                        id = animalId,
                        ranch_id = formState.ranchId,
                        folio_interno = targetFolio,
                        siniiga_chip = formState.siniigaChip?.trim()?.takeIf { it.isNotEmpty() },
                        registro_uno = formState.registroUno?.trim()?.takeIf { it.isNotEmpty() },
                        adn = formState.adn?.trim()?.takeIf { it.isNotEmpty() },
                        breed = formState.breed,
                        color = formState.color.trim(),
                        sex = formState.sex.name,
                        birth_date = formState.birthDate.trim(),
                        weaning_date = formState.weaningDate?.trim()?.takeIf { it.isNotEmpty() },
                        parturition_type = formState.parturitionType.name,
                        conception_method = formState.conceptionMethod.name,
                        sire_id = formState.sireId,
                        dam_id = formState.damId,
                        external_sire_folio = formState.externalSireFolio?.trim()?.takeIf { it.isNotEmpty() },
                        external_dam_folio = formState.externalDamFolio?.trim()?.takeIf { it.isNotEmpty() },
                        entry_reason = formState.entryReason.name,
                        criador_name = formState.criadorName?.trim()?.takeIf { it.isNotEmpty() },
                        photo_s3_key = formState.photoS3Key,
                        birth_event_id = null,
                        mortality_date = null,
                        mortality_cause = null,
                        life_stage = formState.lifeStage.name,
                        reproductive_status = formState.reproductiveStatus.name,
                        status = AnimalStatus.ACTIVE.name,
                        updated_at = occurredAt
                    )
                }

                // Enqueue to pending_sync_events for offline resilience
                val payload = json.encodeToString(formState.copy(id = animalId))
                database.oviAppDatabaseQueries.insertPendingSyncEvent(
                    mutation_id = mutationId,
                    entity_type = "ANIMAL",
                    operation = operation,
                    occurred_at = occurredAt,
                    payload_json = payload,
                    local_photo_path = formState.localPhotoPath,
                    sync_status = "PENDING"
                )
                AnimalSaveResult.Success(animalId, mutationId)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnimalSaveResult.Error(e.message ?: "Error guardando el ejemplar localmente.")
        }
    }

    override suspend fun getAnimalById(id: String): Animal? = withContext(ioDispatcher) {
        val row = database.oviAppDatabaseQueries.selectAnimalById(id).executeAsOneOrNull() ?: return@withContext null
        Animal(
            id = row.id,
            ranchId = row.ranch_id,
            folioInterno = row.folio_interno,
            siniigaChip = row.siniiga_chip,
            registroUno = row.registro_uno,
            adn = row.adn,
            breed = row.breed,
            color = row.color,
            sex = try { Sex.valueOf(row.sex) } catch (_: Exception) { Sex.FEMALE },
            birthDateIso = row.birth_date,
            weaningDateIso = row.weaning_date,
            parturitionType = try { ParturitionType.valueOf(row.parturition_type) } catch (_: Exception) { ParturitionType.SIMPLE },
            conceptionMethod = try { ConceptionMethod.valueOf(row.conception_method) } catch (_: Exception) { ConceptionMethod.MN },
            sireId = row.sire_id,
            damId = row.dam_id,
            externalSireFolio = row.external_sire_folio,
            externalDamFolio = row.external_dam_folio,
            entryReason = row.entry_reason,
            criadorName = row.criador_name,
            photoS3Key = row.photo_s3_key,
            birthEventId = row.birth_event_id,
            mortalityDateIso = row.mortality_date,
            mortalityCause = row.mortality_cause,
            updatedAtIso = row.updated_at,
            lifeStage = try { LifeStage.valueOf(row.life_stage) } catch (_: Exception) { LifeStage.ADULT },
            reproductiveStatus = try { ReproductiveStatus.valueOf(row.reproductive_status) } catch (_: Exception) { ReproductiveStatus.EMPTY },
            status = try { AnimalStatus.valueOf(row.status) } catch (_: Exception) { AnimalStatus.ACTIVE }
        )
    }

    override suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>> =
        getPotentialParentsBySex(ranchId, Sex.FEMALE)

    override suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>> =
        getPotentialParentsBySex(ranchId, Sex.MALE)

    private suspend fun getPotentialParentsBySex(ranchId: String, sex: Sex): List<Pair<String, String>> =
        withContext(ioDispatcher) {
            val pageSize = 200L
            var offset = 0L
            val allParents = mutableListOf<Pair<String, String>>()
            while (true) {
                val batch = database.oviAppDatabaseQueries
                    .selectAnimalsBySexPaged(ranchId, sex.name, pageSize, offset)
                    .executeAsList()
                    .map { it.id to it.folio_interno }
                allParents.addAll(batch)
                if (batch.size.toLong() < pageSize) break
                offset += pageSize
            }
            allParents
        }
}

private fun formatEpochMillisToIsoUtc(epochMillis: Long): String {
    val totalSeconds = epochMillis.floorDiv(1000L)
    val second = totalSeconds.mod(60)
    val totalMinutes = totalSeconds.floorDiv(60L)
    val minute = totalMinutes.mod(60)
    val totalHours = totalMinutes.floorDiv(60L)
    val hour = totalHours.mod(24)
    val daysSinceEpoch = totalHours.floorDiv(24L)

    // Howard Hinnant's civil-from-days algorithm (UTC)
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
