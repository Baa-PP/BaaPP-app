package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.auth.domain.RanchAccessPolicy
import com.agrotech.oviapp.auth.domain.SessionStorage
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.disposal.AnimalUpdatePayload
import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import com.agrotech.oviapp.domain.model.currentEpochMillis
import com.agrotech.oviapp.sync.IsoTimestamp
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class AnimalCreateSyncPayload(
    val id: String,
    val folioInterno: String,
    val siniigaChip: String? = null,
    val registroUno: String? = null,
    val adn: String? = null,
    val breed: String,
    val color: String,
    val sex: String,
    val birthDate: String,
    val weaningDate: String? = null,
    val entryReason: String,
    val criador: String? = null,
    val parturitionType: String,
    val conceptionMethod: String,
    val sireId: String? = null,
    val damId: String? = null,
    val externalSireFolio: String? = null,
    val externalDamFolio: String? = null,
    val lifeStage: String,
    val reproductiveStatus: String,
    val status: String = AnimalStatus.ACTIVE.name,
    val mortalityDate: String? = null,
    val mortalityCause: String? = null,
    val lifeStageAtDeath: String? = null,
    val photoS3Key: String? = null,
)

class SqlDelightAnimalCommandRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = ::currentEpochMillis,
    private val sessionStorage: SessionStorage? = null
) : AnimalCommandRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncQueue = PendingSyncQueue(
        database = database,
        nowMillis = clockMillis,
        currentAccountId = { sessionStorage?.getSession()?.username },
    )

    override suspend fun saveAnimalOffline(formState: AnimalFormState): AnimalSaveResult = withContext(ioDispatcher) {
        val validationError = formState.validate()
        if (validationError != null) {
            return@withContext AnimalSaveResult.ValidationError(validationError)
        }

        val isEdit = !formState.id.isNullOrBlank()
        val activeSession = sessionStorage?.getSession()
        if (sessionStorage != null) {
            if (activeSession?.username.isNullOrBlank()) {
                return@withContext AnimalSaveResult.ValidationError(
                    "No hay una cuenta activa para registrar el cambio."
                )
            }
            val capabilities = activeSession?.let(RanchAccessPolicy::resolveCapabilities)
            val allowed = if (isEdit) capabilities?.canEditGeneralRecords == true else capabilities?.canCapture == true
            if (!allowed) {
                return@withContext AnimalSaveResult.ValidationError(
                    if (isEdit) "No tienes permiso para editar registros de ejemplares."
                    else "No tienes permiso para registrar nuevos ejemplares."
                )
            }
            if (capabilities != null && !capabilities.isMultiRanchScope && activeSession?.ranchId != formState.ranchId) {
                return@withContext AnimalSaveResult.Error("El ejemplar está fuera del rancho autorizado.")
            }
        }

        val targetFolio = formState.folioInterno.trim()
        val cleanSiniiga = formState.siniigaChip?.trim()?.takeIf { it.isNotEmpty() }
        val cleanRegistroUno = formState.registroUno?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAdn = formState.adn?.trim()?.takeIf { it.isNotEmpty() }
        val cleanColor = formState.color.trim()
        val cleanBirthDate = formState.birthDate.trim()
        val cleanWeaningDate = formState.weaningDate?.trim()?.takeIf { it.isNotEmpty() }
        val cleanExtSire = formState.externalSireFolio?.trim()?.takeIf { it.isNotEmpty() }
        val cleanExtDam = formState.externalDamFolio?.trim()?.takeIf { it.isNotEmpty() }
        val cleanCriador = formState.criadorName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanSireId = formState.sireId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDamId = formState.damId?.trim()?.takeIf { it.isNotEmpty() }

        val animalId = if (isEdit) formState.id!! else UuidV7.generate()
        val occurredAt = IsoTimestamp.format(clockMillis())

        try {
            database.transactionWithResult<AnimalSaveResult> {
                val queries = database.oviAppDatabaseQueries
                val existingByFolio = queries
                    .selectAnimalByFolio(formState.ranchId, targetFolio)
                    .executeAsOneOrNull()
                if (existingByFolio != null && existingByFolio.id != animalId) {
                    return@transactionWithResult AnimalSaveResult.DuplicateFolio(
                        "Ya existe un ejemplar con el arete '$targetFolio' en este rancho."
                    )
                }

                if (cleanSiniiga != null) {
                    val existingByChip = queries.selectAnimalBySiniigaChip(cleanSiniiga).executeAsOneOrNull()
                    if (existingByChip != null && existingByChip != animalId) {
                        return@transactionWithResult AnimalSaveResult.ValidationError(
                            "Ya existe un ejemplar con el chip SINIIGA '$cleanSiniiga'."
                        )
                    }
                }

                val existingAnimal = if (isEdit) {
                    val row = queries.selectAnimalById(animalId).executeAsOneOrNull()
                        ?: return@transactionWithResult AnimalSaveResult.Error("No se encontró el ejemplar a editar.")
                    if (row.ranch_id != formState.ranchId) {
                        return@transactionWithResult AnimalSaveResult.Error("El ejemplar no pertenece a este rancho.")
                    }
                    if (hasAncestryCycle(animalId, cleanDamId, cleanSireId)) {
                        return@transactionWithResult AnimalSaveResult.ValidationError(
                            "Se detectó un ciclo genealógico en los ancestros seleccionados."
                        )
                    }
                    row
                } else {
                    null
                }

                val targetRanchId = existingAnimal?.ranch_id ?: formState.ranchId

                if (cleanDamId != null) {
                    val dam = queries.selectAnimalById(cleanDamId).executeAsOneOrNull()
                    if (dam == null || dam.ranch_id != targetRanchId || dam.sex != Sex.FEMALE.name) {
                        return@transactionWithResult AnimalSaveResult.ValidationError(
                            "La madre seleccionada no es una hembra válida de este rancho."
                        )
                    }
                }
                if (cleanSireId != null) {
                    val sire = queries.selectAnimalById(cleanSireId).executeAsOneOrNull()
                    if (sire == null || sire.ranch_id != targetRanchId || sire.sex != Sex.MALE.name) {
                        return@transactionWithResult AnimalSaveResult.ValidationError(
                            "El semental seleccionado no es un macho válido de este rancho."
                        )
                    }
                }

                val mutation = if (isEdit && existingAnimal != null) {
                    val payload = AnimalUpdatePayload(
                        animalId = animalId,
                        folioInterno = targetFolio,
                        siniigaChip = cleanSiniiga,
                        registroUno = cleanRegistroUno,
                        adn = cleanAdn,
                        breed = formState.breed,
                        color = cleanColor,
                        sex = formState.sex.name,
                        birthDate = cleanBirthDate,
                        weaningDate = cleanWeaningDate,
                        entryReason = formState.entryReason.name,
                        criador = cleanCriador,
                        parturitionType = formState.parturitionType.name,
                        conceptionMethod = formState.conceptionMethod.name,
                        sireId = cleanSireId,
                        damId = cleanDamId,
                        externalSireFolio = cleanExtSire,
                        externalDamFolio = cleanExtDam,
                        lifeStage = formState.lifeStage.name,
                        reproductiveStatus = formState.reproductiveStatus.name,
                        status = existingAnimal.status,
                        mortalityDate = existingAnimal.mortality_date?.take(10),
                        mortalityCause = existingAnimal.mortality_cause,
                        lifeStageAtDeath = existingAnimal.life_stage_at_death,
                        photoS3Key = formState.photoS3Key,
                    )
                    syncQueue.writeAndEnqueue(
                        entityType = SyncEntityType.ANIMAL,
                        operation = SyncOperation.UPDATE,
                        payloadJson = json.encodeToString(payload),
                        localPhotoPath = formState.localPhotoPath,
                    ) {
                        queries.updateAnimalFormFields(
                            ranch_id = targetRanchId,
                            folio_interno = targetFolio,
                            siniiga_chip = cleanSiniiga,
                            registro_uno = cleanRegistroUno,
                            adn = cleanAdn,
                            breed = formState.breed,
                            color = cleanColor,
                            sex = formState.sex.name,
                            birth_date = cleanBirthDate,
                            weaning_date = cleanWeaningDate,
                            parturition_type = formState.parturitionType.name,
                            conception_method = formState.conceptionMethod.name,
                            sire_id = cleanSireId,
                            dam_id = cleanDamId,
                            external_sire_folio = cleanExtSire,
                            external_dam_folio = cleanExtDam,
                            entry_reason = formState.entryReason.name,
                            criador_name = cleanCriador,
                            photo_s3_key = formState.photoS3Key,
                            life_stage = formState.lifeStage.name,
                            reproductive_status = formState.reproductiveStatus.name,
                            updated_at = occurredAt,
                            id = animalId,
                        )
                    }
                } else {
                    val payload = AnimalCreateSyncPayload(
                        id = animalId,
                        folioInterno = targetFolio,
                        siniigaChip = cleanSiniiga,
                        registroUno = cleanRegistroUno,
                        adn = cleanAdn,
                        breed = formState.breed,
                        color = cleanColor,
                        sex = formState.sex.name,
                        birthDate = cleanBirthDate,
                        weaningDate = cleanWeaningDate,
                        entryReason = formState.entryReason.name,
                        criador = cleanCriador,
                        parturitionType = formState.parturitionType.name,
                        conceptionMethod = formState.conceptionMethod.name,
                        sireId = cleanSireId,
                        damId = cleanDamId,
                        externalSireFolio = cleanExtSire,
                        externalDamFolio = cleanExtDam,
                        lifeStage = formState.lifeStage.name,
                        reproductiveStatus = formState.reproductiveStatus.name,
                        status = AnimalStatus.ACTIVE.name,
                        photoS3Key = formState.photoS3Key,
                    )
                    syncQueue.writeAndEnqueue(
                        entityType = SyncEntityType.ANIMAL,
                        operation = SyncOperation.CREATE,
                        payloadJson = json.encodeToString(payload),
                        localPhotoPath = formState.localPhotoPath,
                    ) {
                        queries.insertAnimal(
                            id = animalId,
                            ranch_id = targetRanchId,
                            folio_interno = targetFolio,
                            siniiga_chip = cleanSiniiga,
                            registro_uno = cleanRegistroUno,
                            adn = cleanAdn,
                            breed = formState.breed,
                            color = cleanColor,
                            sex = formState.sex.name,
                            birth_date = cleanBirthDate,
                            weaning_date = cleanWeaningDate,
                            parturition_type = formState.parturitionType.name,
                            conception_method = formState.conceptionMethod.name,
                            sire_id = cleanSireId,
                            dam_id = cleanDamId,
                            external_sire_folio = cleanExtSire,
                            external_dam_folio = cleanExtDam,
                            entry_reason = formState.entryReason.name,
                            criador_name = cleanCriador,
                            photo_s3_key = formState.photoS3Key,
                            birth_event_id = null,
                            mortality_date = null,
                            mortality_cause = null,
                            life_stage = formState.lifeStage.name,
                            reproductive_status = formState.reproductiveStatus.name,
                            status = AnimalStatus.ACTIVE.name,
                            updated_at = occurredAt,
                        )
                    }
                }
                AnimalSaveResult.Success(animalId, mutation.mutationId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("UNIQUE", ignoreCase = true) &&
                msg.contains("folio_interno", ignoreCase = true)
            ) {
                AnimalSaveResult.DuplicateFolio(
                    "Ya existe un ejemplar con el arete '$targetFolio' en este rancho."
                )
            } else {
                AnimalSaveResult.Error(e.message ?: "Error guardando el ejemplar localmente.")
            }
        }
    }

    override suspend fun getAnimalById(id: String, ranchId: String?): Animal? = withContext(ioDispatcher) {
        val row = database.oviAppDatabaseQueries.selectAnimalById(id).executeAsOneOrNull() ?: return@withContext null
        if (ranchId != null && row.ranch_id != ranchId) return@withContext null
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

    override suspend fun getLatestLocalPhotoPath(animalId: String): String? = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries
            .selectLatestLocalPhotoByAnimalId(escapeLikePattern(animalId))
            .executeAsOneOrNull()
            ?.local_photo_path
    }

    override suspend fun isLocalPhotoReferencedByPendingSync(localPhotoPath: String): Boolean =
        withContext(ioDispatcher) {
            database.oviAppDatabaseQueries
                .countPendingSyncEventsByLocalPhoto(localPhotoPath)
                .executeAsOne() > 0L
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

    private fun hasAncestryCycle(
        targetAnimalId: String,
        damId: String?,
        sireId: String?
    ): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        damId?.takeIf { it.isNotBlank() }?.let { queue.addLast(it) }
        sireId?.takeIf { it.isNotBlank() }?.let { queue.addLast(it) }

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            if (currentId == targetAnimalId) {
                return true
            }
            if (!visited.add(currentId)) {
                continue
            }
            val parent = database.oviAppDatabaseQueries
                .selectAnimalById(currentId)
                .executeAsOneOrNull() ?: continue
            parent.dam_id?.takeIf { it.isNotBlank() }?.let { queue.addLast(it) }
            parent.sire_id?.takeIf { it.isNotBlank() }?.let { queue.addLast(it) }
        }
        return false
    }

    private fun escapeLikePattern(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
