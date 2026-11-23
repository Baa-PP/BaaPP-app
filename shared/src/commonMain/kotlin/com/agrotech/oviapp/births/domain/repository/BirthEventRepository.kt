package com.agrotech.oviapp.births.domain.repository

import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult

interface BirthEventRepository {
    suspend fun saveBirthEventOffline(draft: BirthEventDraft, todayDateIso: String): BirthSaveResult
    suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>>
    suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>>
}
