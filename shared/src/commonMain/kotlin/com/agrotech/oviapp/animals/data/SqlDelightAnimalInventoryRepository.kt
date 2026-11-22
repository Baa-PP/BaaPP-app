package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.EweInventoryItem
import com.agrotech.oviapp.animals.domain.model.LambInventoryItem
import com.agrotech.oviapp.animals.domain.model.RamInventoryItem
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
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
            query = query.trim(),
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
            query = query.trim(),
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
            query = query.trim(),
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
}
