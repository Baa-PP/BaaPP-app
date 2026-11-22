package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightAnimalCommandRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AnimalCommandRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveAnimalOffline(formState: AnimalFormState): AnimalSaveResult = withContext(ioDispatcher) {
        val validationError = formState.validate()
        if (validationError != null) {
            return@withContext AnimalSaveResult.ValidationError(validationError)
        }

        val targetFolio = formState.folioInterno.trim()
        val existingByFolio = database.oviAppDatabaseQueries
            .selectAnimalByFolio(formState.ranchId, targetFolio)
            .executeAsOneOrNull()

        val isEdit = !formState.id.isNullOrBlank()
        val animalId = if (isEdit) formState.id!! else UuidV7.generate()

        // Check duplicate folio locally
        if (existingByFolio != null && existingByFolio.id != animalId) {
            return@withContext AnimalSaveResult.DuplicateFolio(
                "Ya existe un ejemplar con el arete '$targetFolio' en este rancho."
            )
        }

        val mutationId = UuidV7.generate()
        val occurredAt = "2026-11-22T00:00:00"
        val operation = if (isEdit) "UPDATE" else "CREATE"

        try {
            database.transaction {
                if (isEdit) {
                    database.oviAppDatabaseQueries.updateAnimal(
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
            }
            AnimalSaveResult.Success(animalId, mutationId)
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

    override suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>> = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries.selectAnimalsBySexPaged(ranchId, Sex.FEMALE.name, 200, 0)
            .executeAsList().map { it.id to it.folio_interno }
    }

    override suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>> = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries.selectAnimalsBySexPaged(ranchId, Sex.MALE.name, 200, 0)
            .executeAsList().map { it.id to it.folio_interno }
    }
}
