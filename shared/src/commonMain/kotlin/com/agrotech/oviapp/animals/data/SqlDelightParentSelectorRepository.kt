package com.agrotech.oviapp.animals.data

import com.agrotech.oviapp.animals.domain.model.ParentOption
import com.agrotech.oviapp.animals.domain.repository.ParentSelectorRepository
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightParentSelectorRepository(
    private val database: OviAppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ParentSelectorRepository {

    override suspend fun getPotentialDams(ranchId: String, query: String): List<ParentOption> =
        loadParentsBySex(ranchId = ranchId, sex = Sex.FEMALE, query = query)

    override suspend fun getPotentialSires(ranchId: String, query: String): List<ParentOption> =
        loadParentsBySex(ranchId = ranchId, sex = Sex.MALE, query = query)

    private suspend fun loadParentsBySex(
        ranchId: String,
        sex: Sex,
        query: String
    ): List<ParentOption> = withContext(ioDispatcher) {
        val pageSize = 200L
        var offset = 0L
        val cleanQuery = query.trim()
        val results = mutableListOf<ParentOption>()
        while (true) {
            val batch = database.oviAppDatabaseQueries
                .selectAnimalsBySexPaged(ranchId, sex.name, pageSize, offset)
                .executeAsList()
            batch.forEach { row ->
                val isEligibleStage = row.life_stage == LifeStage.ADULT.name ||
                    (sex == Sex.FEMALE && row.life_stage == LifeStage.EWE_REPLACEMENT.name)
                if (isEligibleStage && (
                        cleanQuery.isEmpty() ||
                            row.folio_interno.contains(cleanQuery, ignoreCase = true) ||
                            (row.siniiga_chip?.contains(cleanQuery, ignoreCase = true) == true)
                        )
                ) {
                    results.add(
                        ParentOption(
                            id = row.id,
                            ranchId = row.ranch_id,
                            folioInterno = row.folio_interno,
                            breed = row.breed,
                            sex = sex
                        )
                    )
                }
            }
            if (batch.size.toLong() < pageSize) break
            offset += pageSize
        }
        results
    }
}
