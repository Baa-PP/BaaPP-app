package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.AnimalDetailWithGenealogy
import com.agrotech.oviapp.animals.domain.model.EweInventoryItem
import com.agrotech.oviapp.animals.domain.model.LambInventoryItem
import com.agrotech.oviapp.animals.domain.model.OffspringSummary
import com.agrotech.oviapp.animals.domain.model.RamInventoryItem
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.db.Local_animals
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightAnimalInventoryRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AnimalInventoryRepository {

    override suspend fun getEwesPaged(
        ranchId: String,
        query: String,
        reproductiveStatus: ReproductiveStatus?,
        limit: Long,
        offset: Long
    ): List<EweInventoryItem> = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries.selectEwesFilteredPaged(
            ranchId = ranchId,
            reproductiveStatus = reproductiveStatus?.name,
            query = escapeLikeQuery(query.trim()),
            limit = limit,
            offset = offset
        ).executeAsList().map { row ->
            EweInventoryItem(
                id = row.id,
                ranchId = row.ranch_id,
                folioInterno = row.folio_interno,
                siniigaChip = row.siniiga_chip,
                breed = row.breed,
                color = row.color,
                birthDate = row.birth_date,
                reproductiveStatus = try {
                    ReproductiveStatus.valueOf(row.reproductive_status)
                } catch (_: Exception) {
                    ReproductiveStatus.EMPTY
                },
                status = try {
                    AnimalStatus.valueOf(row.status)
                } catch (_: Exception) {
                    AnimalStatus.ACTIVE
                }
            )
        }
    }

    override suspend fun getRamsPaged(
        ranchId: String,
        query: String,
        limit: Long,
        offset: Long
    ): List<RamInventoryItem> = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries.selectRamsWithMetricsPaged(
            ranchId = ranchId,
            query = escapeLikeQuery(query.trim()),
            limit = limit,
            offset = offset
        ).executeAsList().map { row ->
            RamInventoryItem(
                id = row.id,
                ranchId = row.ranch_id,
                folioInterno = row.folio_interno,
                siniigaChip = row.siniiga_chip,
                breed = row.breed,
                color = row.color,
                birthDate = row.birth_date,
                reproductiveStatus = try {
                    ReproductiveStatus.valueOf(row.reproductive_status)
                } catch (_: Exception) {
                    ReproductiveStatus.STUD_RAM
                },
                status = try {
                    AnimalStatus.valueOf(row.status)
                } catch (_: Exception) {
                    AnimalStatus.ACTIVE
                },
                latestScrotalCircumferenceCm = row.latest_scrotal_circumference_cm,
                activeOffspringCount = row.active_offspring_count,
                totalOffspringCount = row.total_offspring_count
            )
        }
    }

    override suspend fun getLambsPaged(
        ranchId: String,
        query: String,
        lifeStage: LifeStage?,
        limit: Long,
        offset: Long
    ): List<LambInventoryItem> = withContext(ioDispatcher) {
        database.oviAppDatabaseQueries.selectLambsFilteredPaged(
            ranchId = ranchId,
            lifeStage = lifeStage?.name,
            query = escapeLikeQuery(query.trim()),
            limit = limit,
            offset = offset
        ).executeAsList().map { row ->
            LambInventoryItem(
                id = row.id,
                ranchId = row.ranch_id,
                folioInterno = row.folio_interno,
                siniigaChip = row.siniiga_chip,
                breed = row.breed,
                color = row.color,
                sex = try {
                    Sex.valueOf(row.sex)
                } catch (_: Exception) {
                    Sex.FEMALE
                },
                birthDate = row.birth_date,
                lifeStage = try {
                    LifeStage.valueOf(row.life_stage)
                } catch (_: Exception) {
                    LifeStage.LAMB_LACTATING
                },
                status = try {
                    AnimalStatus.valueOf(row.status)
                } catch (_: Exception) {
                    AnimalStatus.ACTIVE
                },
                damId = row.dam_id,
                sireId = row.sire_id
            )
        }
    }

    override suspend fun getAnimalGenealogy(
        animalId: String,
        ranchId: String?
    ): AnimalDetailWithGenealogy? = withContext(ioDispatcher) {
        val row = database.oviAppDatabaseQueries
            .selectAnimalById(animalId)
            .executeAsOneOrNull() ?: return@withContext null
        if (ranchId != null && row.ranch_id != ranchId) return@withContext null

        val animal = row.toDomainAnimal()
        val dam = animal.damId?.let { damId ->
            database.oviAppDatabaseQueries.selectAnimalById(damId).executeAsOneOrNull()
                ?.takeIf { it.ranch_id == animal.ranchId }
                ?.toDomainAnimal()
        }
        val sire = animal.sireId?.let { sireId ->
            database.oviAppDatabaseQueries.selectAnimalById(sireId).executeAsOneOrNull()
                ?.takeIf { it.ranch_id == animal.ranchId }
                ?.toDomainAnimal()
        }
        val offspring = database.oviAppDatabaseQueries
            .selectOffspringByParentInRanch(ranchId = animal.ranchId, parentId = animal.id)
            .executeAsList()
            .map { childRow ->
                OffspringSummary(
                    id = childRow.id,
                    ranchId = childRow.ranch_id,
                    folioInterno = childRow.folio_interno,
                    siniigaChip = childRow.siniiga_chip,
                    breed = childRow.breed,
                    color = childRow.color,
                    sex = parseSex(childRow.sex),
                    birthDateIso = childRow.birth_date,
                    lifeStage = parseLifeStage(childRow.life_stage, LifeStage.LAMB_LACTATING),
                    status = parseAnimalStatus(childRow.status),
                    mortalityDateIso = childRow.mortality_date,
                    mortalityCause = childRow.mortality_cause
                )
            }

        val localPhotoPath = database.oviAppDatabaseQueries
            .selectLatestLocalPhotoByAnimalId(escapeLikeQuery(animal.id))
            .executeAsOneOrNull()
            ?.local_photo_path

        AnimalDetailWithGenealogy(
            animal = animal,
            dam = dam,
            sire = sire,
            offspring = offspring,
            localPhotoPath = localPhotoPath
        )
    }

    private fun Local_animals.toDomainAnimal(): Animal = Animal(
        id = id,
        ranchId = ranch_id,
        folioInterno = folio_interno,
        siniigaChip = siniiga_chip,
        registroUno = registro_uno,
        adn = adn,
        breed = breed,
        color = color,
        sex = parseSex(sex),
        birthDateIso = birth_date,
        weaningDateIso = weaning_date,
        parturitionType = try { ParturitionType.valueOf(parturition_type) } catch (_: Exception) { ParturitionType.SIMPLE },
        conceptionMethod = try { ConceptionMethod.valueOf(conception_method) } catch (_: Exception) { ConceptionMethod.MN },
        sireId = sire_id,
        damId = dam_id,
        externalSireFolio = external_sire_folio,
        externalDamFolio = external_dam_folio,
        entryReason = entry_reason,
        criadorName = criador_name,
        photoS3Key = photo_s3_key,
        birthEventId = birth_event_id,
        mortalityDateIso = mortality_date,
        mortalityCause = mortality_cause,
        updatedAtIso = updated_at,
        lifeStage = parseLifeStage(life_stage, LifeStage.ADULT),
        reproductiveStatus = try { ReproductiveStatus.valueOf(reproductive_status) } catch (_: Exception) { ReproductiveStatus.EMPTY },
        status = parseAnimalStatus(status)
    )

    private fun parseSex(raw: String): Sex = try {
        Sex.valueOf(raw)
    } catch (_: Exception) {
        Sex.FEMALE
    }

    private fun parseLifeStage(raw: String, fallback: LifeStage): LifeStage = try {
        LifeStage.valueOf(raw)
    } catch (_: Exception) {
        fallback
    }

    private fun parseAnimalStatus(raw: String): AnimalStatus = try {
        AnimalStatus.valueOf(raw)
    } catch (_: Exception) {
        AnimalStatus.ACTIVE
    }

    private fun escapeLikeQuery(raw: String): String {
        if (raw.isEmpty()) return raw
        return raw
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
