package com.agrotech.oviapp.births.domain.model

import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.weights.Decimals
import kotlinx.serialization.Serializable

@Serializable
data class LiveOffspringDraft(
    val folioInterno: String = "",
    val sex: Sex = Sex.FEMALE,
    val color: String = "",
    val birthWeightKg: String = ""
)

@Serializable
data class BirthEventDraft(
    val ranchId: String,
    val damId: String = "",
    val sireId: String? = null,
    val conceptionMethod: ConceptionMethod = ConceptionMethod.MN,
    val birthDateIso: String = "",
    val stillborns: Int = 0,
    val liveOffspring: List<LiveOffspringDraft> = listOf(LiveOffspringDraft()),
    val localPhotoPath: String? = null,
    val registeredByUserId: String? = null,
    val notes: String? = null
) {
    fun deducedParturitionType(): ParturitionType {
        val total = liveOffspring.size + stillborns.coerceAtLeast(0)
        return when {
            total <= 1 -> ParturitionType.SIMPLE
            total == 2 -> ParturitionType.TWIN
            total == 3 -> ParturitionType.TRIPLET
            total == 4 -> ParturitionType.QUADRUPLET
            else -> ParturitionType.QUINTUPLET
        }
    }

    fun validate(todayDateIso: String): String? {
        if (damId.isBlank()) {
            return "Debes seleccionar a la madre del parto."
        }
        if (!sireId.isNullOrBlank() && sireId.trim() == damId.trim()) {
            return "La madre y el semental no pueden ser el mismo ejemplar."
        }
        val cleanDate = birthDateIso.trim()
        val birthDay = parseIsoToEpochDay(cleanDate)
            ?: return "La fecha de parto debe tener formato válido YYYY-MM-DD."
        val todayDay = parseIsoToEpochDay(todayDateIso.trim())
            ?: return "La fecha de referencia debe tener formato válido YYYY-MM-DD."
        val daysElapsed = todayDay - birthDay
        if (daysElapsed < 0L) {
            return "La fecha de parto no puede ser futura."
        }
        if (daysElapsed > 30L) {
            return "Ventana de 30 días cerrada. Solicite el alta auditada a la Asociación/Técnico con su evidencia fotográfica."
        }
        if (stillborns < 0) {
            return "El número de nacidos muertos no puede ser negativo."
        }
        val total = liveOffspring.size + stillborns
        if (total !in 1..5) {
            return "El parto debe tener entre 1 y 5 crías en total (vivas + muertas)."
        }
        if (localPhotoPath.isNullOrBlank()) {
            return "La fotografía del parto es obligatoria (~150-200 KB)."
        }

        val seenFolios = mutableSetOf<String>()
        liveOffspring.forEachIndexed { index, child ->
            val label = "Cría #${index + 1}"
            val folio = child.folioInterno.trim()
            if (folio.isEmpty()) {
                return "$label: el arete/folio interno es obligatorio."
            }
            if (folio.length > 50) {
                return "$label: el folio interno no puede exceder 50 caracteres."
            }
            if (!seenFolios.add(folio)) {
                return "$label: el arete '$folio' está repetido dentro del mismo parto."
            }
            if (child.color.trim().isEmpty()) {
                return "$label: el color o capa es obligatorio."
            }
            val parsedWeight = Decimals.parseHundredths(child.birthWeightKg)
            if (parsedWeight == null || !parsedWeight.exact || parsedWeight.hundredths !in 1L..2500L) {
                return "$label: el peso al nacer debe ser un número positivo válido de hasta 2 decimales (ej. 3.5 kg)."
            }
        }
        return null
    }

    companion object {
        private val ISO_DATE_REGEX = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        fun isValidIsoDate(value: String): Boolean = parseIsoToEpochDay(value) != null

        private fun parseIsoToEpochDay(value: String): Long? {
            val match = ISO_DATE_REGEX.matchEntire(value) ?: return null
            val year = match.groupValues[1].toIntOrNull() ?: return null
            val month = match.groupValues[2].toIntOrNull() ?: return null
            val day = match.groupValues[3].toIntOrNull() ?: return null
            if (year !in 1900..9999 || month !in 1..12) return null
            val isLeapYear = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
            val maxDay = when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (isLeapYear) 29 else 28
                else -> return null
            }
            if (day !in 1..maxDay) return null
            val y = year.toLong() - if (month <= 2) 1L else 0L
            val era = (if (y >= 0) y else y - 399L) / 400L
            val yoe = y - era * 400L
            val m = month.toLong()
            val doy = (153L * (m + if (m > 2) -3L else 9L) + 2L) / 5L + day.toLong() - 1L
            val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
            return era * 146097L + doe - 719468L
        }
    }
}

sealed class BirthSaveResult {
    data class Success(
        val birthEventId: String,
        val offspringIds: List<String>,
        val mutationId: String
    ) : BirthSaveResult()
    data class DuplicateFolio(val message: String) : BirthSaveResult()
    data class ValidationError(val message: String) : BirthSaveResult()
    data class Error(val message: String) : BirthSaveResult()
}

data class SavedBirthEvent(
    val id: String,
    val ranchId: String,
    val damId: String,
    val damFolio: String?,
    val sireId: String?,
    val sireFolio: String?,
    val birthDateIso: String,
    val liveBirths: Int,
    val stillborns: Int,
    val parturitionType: String,
    val conceptionMethod: String,
    val hasPhoto: Boolean,
    val registeredByUserId: String?,
    val notes: String?,
    val createdAt: String
)

object BirthImmutabilityPolicy {
    const val AUDITED_CORRECTION_NOTICE =
        "Los nacimientos guardados son inmutables. No se pueden editar cantidades ni eliminar registros en la app. Si hubo un error, repórtalo con foto a la Asociación/Técnico para su corrección auditada."
}
