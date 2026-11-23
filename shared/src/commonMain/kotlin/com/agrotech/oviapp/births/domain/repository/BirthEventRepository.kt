package com.agrotech.oviapp.births.domain.repository

import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.model.SavedBirthEvent

interface BirthEventRepository {
    suspend fun saveBirthEventOffline(
        draft: BirthEventDraft,
        todayDateIso: String,
        draftKey: String? = null
    ): BirthSaveResult
    suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>>
    suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>>
    suspend fun getSavedBirthEvents(ranchId: String): List<SavedBirthEvent> = emptyList()
    suspend fun isLocalPhotoReferenced(localPhotoPath: String): Boolean = false
}
