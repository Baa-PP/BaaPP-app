package com.agrotech.oviapp.births.data

import com.agrotech.oviapp.auth.domain.SessionStorage
import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.model.SavedBirthEvent
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import com.agrotech.oviapp.domain.model.currentEpochMillis
import com.agrotech.oviapp.photos.LocalPhotoFileOps
import com.agrotech.oviapp.sync.IsoTimestamp
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import com.agrotech.oviapp.weights.Decimals
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
    private val clockMillis: () -> Long = ::currentEpochMillis,
    private val photoExistsCheck: (String) -> Boolean = LocalPhotoFileOps::exists,
    private val sessionStorage: SessionStorage? = null,
) : BirthEventRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncQueue = PendingSyncQueue(
        database = database,
        nowMillis = clockMillis,
        currentAccountId = { sessionStorage?.getSession()?.username },
    )

    override suspend fun saveBirthEventOffline(
        draft: BirthEventDraft,
        todayDateIso: String,
        draftKey: String?
    ): BirthSaveResult = withContext(ioDispatcher) {
        val validationError = draft.validate(todayDateIso)
        if (validationError != null) {
            return@withContext BirthSaveResult.ValidationError(validationError)
        }
        if (sessionStorage != null && sessionStorage.getSession()?.username.isNullOrBlank()) {
            return@withContext BirthSaveResult.ValidationError(
                "No hay una cuenta activa para registrar el parto."
            )
        }

        val cleanPhotoPath = draft.localPhotoPath?.trim()
        if (cleanPhotoPath.isNullOrEmpty() || !photoExistsCheck(cleanPhotoPath)) {
            return@withContext BirthSaveResult.ValidationError(
                "La fotografía seleccionada no existe o no se puede leer."
            )
        }
        val persistedPhotoPath = if (LocalPhotoFileOps.exists(cleanPhotoPath)) {
            LocalPhotoFileOps.canonicalize(cleanPhotoPath) ?: cleanPhotoPath
        } else {
            cleanPhotoPath
        }

        val birthEventId = UuidV7.generate()
        val occurredAt = IsoTimestamp.format(clockMillis())
        val parturitionType = draft.deducedParturitionType()
        val cleanBirthDate = draft.birthDateIso.trim()
        val normalizedSireId = draft.sireId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedDraftKey = draftKey?.trim()?.takeIf { it.isNotEmpty() }

        try {
            database.transactionWithResult {
                val queries = database.oviAppDatabaseQueries
                if (normalizedDraftKey != null) {
                    val existingByKey = queries
                        .selectBirthEventByDraftKey(normalizedDraftKey)
                        .executeAsOneOrNull()
                    if (existingByKey != null) {
                        return@transactionWithResult BirthSaveResult.ValidationError(
                            "Este evento de parto ya fue guardado."
                        )
                    }
                }

                val dam = queries
                    .selectAnimalById(draft.damId)
                    .executeAsOneOrNull()
                if (dam == null || dam.ranch_id != draft.ranchId || dam.sex != Sex.FEMALE.name) {
                    return@transactionWithResult BirthSaveResult.ValidationError(
                        "La madre seleccionada no es válida para este rancho."
                    )
                }
                if (dam.status != AnimalStatus.ACTIVE.name) {
                    return@transactionWithResult BirthSaveResult.ValidationError(
                        "La madre seleccionada no está activa en el rebaño."
                    )
                }
                if (cleanBirthDate < dam.birth_date.take(10)) {
                    return@transactionWithResult BirthSaveResult.ValidationError(
                        "La fecha de parto no puede ser anterior al nacimiento de la madre."
                    )
                }

                if (normalizedSireId != null) {
                    val sire = queries
                        .selectAnimalById(normalizedSireId)
                        .executeAsOneOrNull()
                    if (sire == null || sire.ranch_id != draft.ranchId || sire.sex != Sex.MALE.name) {
                        return@transactionWithResult BirthSaveResult.ValidationError(
                            "El semental seleccionado no es válido para este rancho."
                        )
                    }
                }

                // Validate all offspring folios before inserting any rows so a conflict never partially commits
                for (child in draft.liveOffspring) {
                    val cleanFolio = child.folioInterno.trim()
                    val existing = queries
                        .selectAnimalByFolio(draft.ranchId, cleanFolio)
                        .executeAsOneOrNull()
                    if (existing != null) {
                        return@transactionWithResult BirthSaveResult.DuplicateFolio(
                            "Ya existe un ejemplar con el arete '$cleanFolio' en este rancho."
                        )
                    }
                }

                data class PreparedLamb(
                    val childId: String,
                    val weightId: String,
                    val folio: String,
                    val color: String,
                    val sex: Sex,
                    val weightKg: Double,
                )

                val preparedLambs = draft.liveOffspring.map { child ->
                    val parsed = Decimals.parseHundredths(child.birthWeightKg)
                        ?: return@transactionWithResult BirthSaveResult.ValidationError(
                            "El peso al nacer de '${child.folioInterno.trim()}' es inválido."
                        )
                    PreparedLamb(
                        childId = UuidV7.generate(),
                        weightId = UuidV7.generate(),
                        folio = child.folioInterno.trim(),
                        color = child.color.trim(),
                        sex = child.sex,
                        weightKg = parsed.hundredths / 100.0,
                    )
                }

                val syncPayload = SyncBirthCreatePayload(
                    id = birthEventId,
                    damId = draft.damId,
                    sireId = normalizedSireId,
                    externalSireFolio = null,
                    birthDate = cleanBirthDate,
                    liveBornCount = preparedLambs.size,
                    stillbornCount = draft.stillborns,
                    conceptionMethod = draft.conceptionMethod.name,
                    evidencePhotoS3Key = null,
                    lambs = preparedLambs.map { lamb ->
                        SyncBirthLambPayload(
                            id = lamb.childId,
                            folioInterno = lamb.folio,
                            breed = dam.breed,
                            color = lamb.color,
                            sex = lamb.sex.name,
                            lifeStage = LifeStage.LAMB_LACTATING.name,
                            reproductiveStatus = ReproductiveStatus.EMPTY.name,
                            birthWeightKg = lamb.weightKg,
                            weightId = lamb.weightId,
                        )
                    }
                )

                val mutation = syncQueue.writeAndEnqueue(
                    entityType = SyncEntityType.BIRTH_EVENT,
                    operation = SyncOperation.CREATE,
                    payloadJson = json.encodeToString(syncPayload),
                    localPhotoPath = persistedPhotoPath,
                ) {
                    for (lamb in preparedLambs) {
                        queries.insertAnimal(
                            id = lamb.childId,
                            ranch_id = draft.ranchId,
                            folio_interno = lamb.folio,
                            siniiga_chip = null,
                            registro_uno = null,
                            adn = null,
                            breed = dam.breed,
                            color = lamb.color,
                            sex = lamb.sex.name,
                            birth_date = cleanBirthDate,
                            weaning_date = null,
                            parturition_type = parturitionType.name,
                            conception_method = draft.conceptionMethod.name,
                            sire_id = normalizedSireId,
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

                        queries.insertWeightMeasurement(
                            id = lamb.weightId,
                            ranch_id = draft.ranchId,
                            animal_id = lamb.childId,
                            milestone = "BIRTH",
                            weight_kg = lamb.weightKg,
                            scrotal_circumference_cm = null,
                            measurement_date = cleanBirthDate,
                            notes = null,
                            created_at = occurredAt,
                            updated_at = occurredAt
                        )
                    }

                    if (preparedLambs.isNotEmpty()) {
                        queries.markMotherLactatingAfterBirth(
                            updatedAt = occurredAt,
                            id = draft.damId,
                            ranchId = draft.ranchId,
                        )
                    }

                    queries.insertBirthEvent(
                        id = birthEventId,
                        ranch_id = draft.ranchId,
                        dam_id = draft.damId,
                        sire_id = normalizedSireId,
                        birth_date = cleanBirthDate,
                        live_births = preparedLambs.size.toLong(),
                        stillborns = draft.stillborns.toLong(),
                        parturition_type = parturitionType.name,
                        conception_method = draft.conceptionMethod.name,
                        photo_s3_key = null,
                        local_photo_path = persistedPhotoPath,
                        registered_by_user_id = draft.registeredByUserId,
                        notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                        draft_key = normalizedDraftKey,
                        created_at = occurredAt,
                        updated_at = occurredAt
                    )
                }

                BirthSaveResult.Success(
                    birthEventId = birthEventId,
                    offspringIds = preparedLambs.map { it.childId },
                    mutationId = mutation.mutationId
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BirthSaveResult.Error(e.message ?: "Error guardando el evento de parto.")
        }
    }

    override suspend fun isLocalPhotoReferenced(localPhotoPath: String): Boolean =
        withContext(ioDispatcher) {
            val candidates = buildSet {
                add(localPhotoPath)
                LocalPhotoFileOps.canonicalize(localPhotoPath)?.let(::add)
            }
            candidates.any { path ->
                database.oviAppDatabaseQueries.countBirthEventsByLocalPhoto(path).executeAsOne() > 0L ||
                    database.oviAppDatabaseQueries.countPendingSyncEventsByLocalPhoto(path).executeAsOne() > 0L
            }
        }

    override suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>> =
        loadParentsBySex(ranchId, Sex.FEMALE)

    override suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>> =
        loadParentsBySex(ranchId, Sex.MALE)

    override suspend fun getSavedBirthEvents(ranchId: String): List<SavedBirthEvent> =
        withContext(ioDispatcher) {
            database.transactionWithResult {
                val queries = database.oviAppDatabaseQueries
                val folioCache = mutableMapOf<String, String?>()
                fun resolveFolio(animalId: String?): String? {
                    if (animalId == null) return null
                    return folioCache.getOrPut(animalId) {
                        queries.selectAnimalById(animalId).executeAsOneOrNull()
                            ?.takeIf { it.ranch_id == ranchId }
                            ?.folio_interno
                    }
                }

                val pageSize = 200L
                var offset = 0L
                val result = mutableListOf<SavedBirthEvent>()
                while (true) {
                    val batch = queries
                        .selectBirthEventsByRanchPaged(ranchId, pageSize, offset)
                        .executeAsList()
                    batch.forEach { row ->
                        result.add(
                            SavedBirthEvent(
                                id = row.id,
                                ranchId = row.ranch_id,
                                damId = row.dam_id,
                                damFolio = resolveFolio(row.dam_id),
                                sireId = row.sire_id,
                                sireFolio = resolveFolio(row.sire_id),
                                birthDateIso = row.birth_date,
                                liveBirths = row.live_births.toInt(),
                                stillborns = row.stillborns.toInt(),
                                parturitionType = row.parturition_type,
                                conceptionMethod = row.conception_method,
                                hasPhoto = !row.local_photo_path.isNullOrBlank() || !row.photo_s3_key.isNullOrBlank(),
                                registeredByUserId = row.registered_by_user_id,
                                notes = row.notes,
                                createdAt = row.created_at
                            )
                        )
                    }
                    if (batch.size.toLong() < pageSize) break
                    offset += pageSize
                }
                result
            }
        }

    private suspend fun loadParentsBySex(ranchId: String, sex: Sex): List<Pair<String, String>> =
        withContext(ioDispatcher) {
            database.transactionWithResult {
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
        }

    @Serializable
    private data class SyncBirthLambPayload(
        val id: String,
        val folioInterno: String,
        val siniigaChip: String? = null,
        val registroUno: String? = null,
        val adn: String? = null,
        val breed: String,
        val color: String,
        val sex: String,
        val criador: String? = null,
        val lifeStage: String = LifeStage.LAMB_LACTATING.name,
        val reproductiveStatus: String = ReproductiveStatus.EMPTY.name,
        val birthWeightKg: Double? = null,
        val weightId: String? = null,
        val photoS3Key: String? = null,
    )

    @Serializable
    private data class SyncBirthCreatePayload(
        val id: String,
        val damId: String,
        val sireId: String? = null,
        val externalSireFolio: String? = null,
        val birthDate: String,
        val liveBornCount: Int,
        val stillbornCount: Int = 0,
        val conceptionMethod: String,
        val evidencePhotoS3Key: String? = null,
        val lambs: List<SyncBirthLambPayload> = emptyList(),
    )
}
