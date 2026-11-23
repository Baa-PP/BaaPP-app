package com.agrotech.oviapp.domain.model

sealed class SiniigaValidationResult {
    data class Valid(val normalizedChip: String?) : SiniigaValidationResult()
    data class Invalid(val message: String) : SiniigaValidationResult()
}

/**
 * Validates Mexican SINIIGA ear-tag/chip identifiers (RF-AND-02):
 * Optional at birth; when provided, must be 10 to 15 numeric digits starting with "484".
 */
object SiniigaValidator {
    private val SINIIGA_CHIP_REGEX = Regex("^484\\d{7,12}$")

    fun normalizeAndValidate(rawChip: String?): SiniigaValidationResult {
        val normalized = rawChip?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SiniigaValidationResult.Valid(null)

        return if (SINIIGA_CHIP_REGEX.matches(normalized)) {
            SiniigaValidationResult.Valid(normalized)
        } else {
            SiniigaValidationResult.Invalid(
                "El chip SINIIGA debe tener entre 10 y 15 dígitos numéricos iniciando con 484."
            )
        }
    }

    fun isValid(rawChip: String?): Boolean =
        normalizeAndValidate(rawChip) is SiniigaValidationResult.Valid
}
