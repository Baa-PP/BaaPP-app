package com.agrotech.oviapp.animals.domain.model

import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.Sex

data class OffspringSummary(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val siniigaChip: String?,
    val breed: String,
    val color: String,
    val sex: Sex,
    val birthDateIso: String,
    val lifeStage: LifeStage,
    val status: AnimalStatus,
    val mortalityDateIso: String? = null,
    val mortalityCause: String? = null
)

data class AnimalDetailWithGenealogy(
    val animal: Animal,
    val dam: Animal?,
    val sire: Animal?,
    val offspring: List<OffspringSummary>
)
