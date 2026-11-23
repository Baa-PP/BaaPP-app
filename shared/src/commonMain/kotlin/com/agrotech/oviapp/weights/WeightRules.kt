package com.agrotech.oviapp.weights

import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.LocalDate

/** Mismos hitos que BE-009 (`WeightMilestone` del backend). */
enum class WeightMilestone { BIRTH, WEANING, DAY_120, DAY_150, EVALUATION }

/** Mismos códigos que devuelve el backend, para que la app y el servidor rechacen lo mismo. */
enum class WeightError {
    INVALID_WEIGHT_KG,
    INVALID_WEIGHT_PRECISION,
    INVALID_SCROTAL_CIRCUMFERENCE,
    INVALID_SCROTAL_PRECISION,
    SCROTAL_FEMALE_NOT_ALLOWED,
    WEIGHT_DATE_BEFORE_BIRTH,
    ANIMAL_DECEASED,
}

data class AnimalForWeighing(
    val id: String,
    val ranchId: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val deceased: Boolean,
)

/** Lo que el usuario teclea en el formulario; acepta coma o punto decimal. */
data class WeightInput(
    val milestone: WeightMilestone,
    val weightKg: String,
    val scrotalCircumferenceCm: String?,
    val date: LocalDate,
    val notes: String? = null,
)

/** Pesaje ya validado. Las medidas van en centésimas para no arrastrar errores de punto flotante. */
data class ValidWeight(
    val milestone: WeightMilestone,
    val weightHundredths: Long,
    val scrotalHundredths: Long?,
    val date: LocalDate,
    val notes: String?,
)

sealed interface WeightValidation {
    data class Valid(val weight: ValidWeight) : WeightValidation
    data class Invalid(val errors: List<WeightError>) : WeightValidation
}

object WeightRules {
    private const val MAX_WEIGHT_HUNDREDTHS = 99_999L // 999.99 kg
    private const val MAX_SCROTAL_HUNDREDTHS = 9_999L // 99.99 cm

    fun scrotalCircumferenceAvailable(sex: Sex): Boolean = sex == Sex.MALE

    fun validate(animal: AnimalForWeighing, input: WeightInput): WeightValidation {
        if (animal.deceased) return WeightValidation.Invalid(listOf(WeightError.ANIMAL_DECEASED))

        val errors = mutableListOf<WeightError>()

        val weight = Decimals.parseHundredths(input.weightKg)
        when {
            weight == null || weight.hundredths <= 0 || weight.hundredths > MAX_WEIGHT_HUNDREDTHS ->
                errors += WeightError.INVALID_WEIGHT_KG
            !weight.exact -> errors += WeightError.INVALID_WEIGHT_PRECISION
        }

        val scrotalText = input.scrotalCircumferenceCm?.takeIf { it.isNotBlank() }
        val scrotal = scrotalText?.let { Decimals.parseHundredths(it) }
        if (scrotalText != null) {
            when {
                !scrotalCircumferenceAvailable(animal.sex) -> errors += WeightError.SCROTAL_FEMALE_NOT_ALLOWED
                scrotal == null || scrotal.hundredths <= 0 || scrotal.hundredths > MAX_SCROTAL_HUNDREDTHS ->
                    errors += WeightError.INVALID_SCROTAL_CIRCUMFERENCE
                !scrotal.exact -> errors += WeightError.INVALID_SCROTAL_PRECISION
            }
        }

        if (input.date < animal.birthDate) errors += WeightError.WEIGHT_DATE_BEFORE_BIRTH

        if (errors.isNotEmpty()) return WeightValidation.Invalid(errors)
        return WeightValidation.Valid(
            ValidWeight(
                milestone = input.milestone,
                weightHundredths = weight!!.hundredths,
                scrotalHundredths = scrotal?.hundredths,
                date = input.date,
                notes = input.notes?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }
}
