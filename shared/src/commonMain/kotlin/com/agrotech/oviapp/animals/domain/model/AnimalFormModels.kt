package com.agrotech.oviapp.animals.domain.model

import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.SiniigaValidator
import kotlinx.serialization.Serializable

@Serializable
data class AnimalFormState(
    val id: String? = null,
    val ranchId: String,
    val folioInterno: String = "",
    val siniigaChip: String? = null,
    val registroUno: String? = null,
    val adn: String? = null,
    val breed: String = "Pelifolk",
    val color: String = "",
    val sex: Sex = Sex.FEMALE,
    val birthDate: String = "",
    val weaningDate: String? = null,
    val parturitionType: ParturitionType = ParturitionType.SIMPLE,
    val conceptionMethod: ConceptionMethod = ConceptionMethod.MN,
    val sireId: String? = null,
    val damId: String? = null,
    val externalSireFolio: String? = null,
    val externalDamFolio: String? = null,
    val entryReason: EntryReason = EntryReason.BIRTH,
    val criadorName: String? = null,
    val localPhotoPath: String? = null,
    val photoS3Key: String? = null,
    val lifeStage: LifeStage = LifeStage.ADULT,
    val reproductiveStatus: ReproductiveStatus = ReproductiveStatus.EMPTY
) {
    fun validate(): String? {
        val folio = folioInterno.trim()
        if (folio.isEmpty()) {
            return "El arete/folio interno es obligatorio."
        }
        if (folio.length > 50) {
            return "El folio interno no puede exceder 50 caracteres."
        }
        val cleanColor = color.trim()
        if (cleanColor.isEmpty()) {
            return "El color o capa es obligatorio."
        }
        if (birthDate.trim().isEmpty()) {
            return "La fecha de nacimiento es obligatoria (YYYY-MM-DD)."
        }
        if (!SiniigaValidator.isValid(siniigaChip)) {
            return "El chip SINIIGA debe tener entre 10 y 15 dígitos numéricos iniciando con 484."
        }
        return null
    }
}

sealed class AnimalSaveResult {
    data class Success(val animalId: String, val mutationId: String) : AnimalSaveResult()
    data class DuplicateFolio(val message: String) : AnimalSaveResult()
    data class ValidationError(val message: String) : AnimalSaveResult()
    data class Error(val message: String) : AnimalSaveResult()
}
