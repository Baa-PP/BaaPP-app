package com.agrotech.oviapp.animals.domain.repository

import com.agrotech.oviapp.animals.domain.model.ParentOption

interface ParentSelectorRepository {
    suspend fun getPotentialDams(ranchId: String, query: String = ""): List<ParentOption>
    suspend fun getPotentialSires(ranchId: String, query: String = ""): List<ParentOption>
}
