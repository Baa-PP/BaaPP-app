package com.agrotech.oviapp.animals.domain.repository

import com.agrotech.oviapp.animals.domain.model.EweInventoryItem
import com.agrotech.oviapp.animals.domain.model.LambInventoryItem
import com.agrotech.oviapp.animals.domain.model.RamInventoryItem
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus

interface AnimalInventoryRepository {
    suspend fun getEwesPaged(
        ranchId: String,
        query: String = "",
        reproductiveStatus: ReproductiveStatus? = null,
        limit: Long = 50,
        offset: Long = 0
    ): List<EweInventoryItem>

    suspend fun getRamsPaged(
        ranchId: String,
        query: String = "",
        limit: Long = 50,
        offset: Long = 0
    ): List<RamInventoryItem>

    suspend fun getLambsPaged(
        ranchId: String,
        query: String = "",
        lifeStage: LifeStage? = null,
        limit: Long = 50,
        offset: Long = 0
    ): List<LambInventoryItem>
}
