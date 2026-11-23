package com.agrotech.oviapp.animals.domain.model

import com.agrotech.oviapp.domain.model.AnimalAge
import com.agrotech.oviapp.domain.model.AnimalAgeCalculator
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex

enum class InventoryTab(val title: String) {
    EWES("Hembras"),
    RAMS("Sementales"),
    LAMBS("Crías")
}

data class EweInventoryItem(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val siniigaChip: String?,
    val breed: String,
    val color: String,
    val birthDate: String,
    val reproductiveStatus: ReproductiveStatus,
    val status: AnimalStatus
) {
    fun calculateAge(referenceDateIso: String): AnimalAge? =
        AnimalAgeCalculator.calculateAge(birthDate, referenceDateIso)
}

data class RamInventoryItem(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val siniigaChip: String?,
    val breed: String,
    val color: String,
    val birthDate: String,
    val reproductiveStatus: ReproductiveStatus,
    val status: AnimalStatus,
    val latestScrotalCircumferenceCm: Double?,
    val activeOffspringCount: Long,
    val totalOffspringCount: Long
) {
    fun calculateAge(referenceDateIso: String): AnimalAge? =
        AnimalAgeCalculator.calculateAge(birthDate, referenceDateIso)
}

data class LambInventoryItem(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val siniigaChip: String?,
    val breed: String,
    val color: String,
    val sex: Sex,
    val birthDate: String,
    val lifeStage: LifeStage,
    val status: AnimalStatus,
    val damId: String?,
    val sireId: String?
) {
    fun calculateAge(referenceDateIso: String): AnimalAge? =
        AnimalAgeCalculator.calculateAge(birthDate, referenceDateIso)
}
