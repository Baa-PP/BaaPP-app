package com.agrotech.oviapp.animals.domain.repository

import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.domain.model.Animal

interface AnimalCommandRepository {
    suspend fun saveAnimalOffline(formState: AnimalFormState): AnimalSaveResult
    suspend fun getAnimalById(id: String): Animal?
    suspend fun getLatestLocalPhotoPath(animalId: String): String? = null
    suspend fun getPotentialDams(ranchId: String): List<Pair<String, String>> // id to folioInterno
    suspend fun getPotentialSires(ranchId: String): List<Pair<String, String>> // id to folioInterno
}
