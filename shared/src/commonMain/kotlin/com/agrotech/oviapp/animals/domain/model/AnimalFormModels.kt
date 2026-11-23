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
        val cleanBirthDate = birthDate.trim()
        if (cleanBirthDate.isEmpty() || !isValidIsoDate(cleanBirthDate)) {
            return "La fecha de nacimiento debe tener formato válido YYYY-MM-DD."
        }
        val cleanWeaningDate = weaningDate?.trim()
        if (!cleanWeaningDate.isNullOrEmpty()) {
            if (!isValidIsoDate(cleanWeaningDate)) {
                return "La fecha de destete debe tener formato válido YYYY-MM-DD."
            }
            if (cleanWeaningDate < cleanBirthDate) {
                return "La fecha de destete no puede ser anterior a la fecha de nacimiento."
            }
        }
        if (!id.isNullOrBlank()) {
            if (damId == id) {
                return "Un ejemplar no puede ser su propia madre."
            }
            if (sireId == id) {
                return "Un ejemplar no puede ser su propio semental."
            }
        }
        if (!SiniigaValidator.isValid(siniigaChip)) {
            return "El chip SINIIGA debe tener entre 10 y 15 dígitos numéricos iniciando con 484."
        }
        if (sex == Sex.MALE && lifeStage == LifeStage.EWE_REPLACEMENT) {
            return "Un macho no puede registrarse en etapa de reemplazo de hembra."
        }
        if (sex == Sex.FEMALE && reproductiveStatus == ReproductiveStatus.STUD_RAM) {
            return "Una hembra no puede tener estado reproductivo de semental."
        }
        if (sex == Sex.MALE &&
            (reproductiveStatus == ReproductiveStatus.PREGNANT || reproductiveStatus == ReproductiveStatus.LACTATING)
        ) {
            return "Un macho no puede tener estado reproductivo gestante o lactando."
        }
        if (!damId.isNullOrBlank() && !externalDamFolio.isNullOrBlank()) {
            return "Selecciona una madre del rebaño o captura un folio externo, no ambos."
        }
        if (!sireId.isNullOrBlank() && !externalSireFolio.isNullOrBlank()) {
            return "Selecciona un semental del rebaño o captura un folio externo, no ambos."
        }
        return null
    }

    companion object {
        private val ISO_DATE_REGEX = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        fun isValidIsoDate(value: String): Boolean {
            val match = ISO_DATE_REGEX.matchEntire(value) ?: return false
            val year = match.groupValues[1].toIntOrNull() ?: return false
            val month = match.groupValues[2].toIntOrNull() ?: return false
            val day = match.groupValues[3].toIntOrNull() ?: return false
            if (year !in 1900..9999 || month !in 1..12) return false
            val isLeapYear = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
            val maxDay = when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (isLeapYear) 29 else 28
                else -> return false
            }
            return day in 1..maxDay
        }
    }
}

sealed class AnimalSaveResult {
    data class Success(val animalId: String, val mutationId: String) : AnimalSaveResult()
    data class DuplicateFolio(val message: String) : AnimalSaveResult()
    data class ValidationError(val message: String) : AnimalSaveResult()
    data class Error(val message: String) : AnimalSaveResult()
}
