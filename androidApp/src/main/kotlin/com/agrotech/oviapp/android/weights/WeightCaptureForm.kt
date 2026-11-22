package com.agrotech.oviapp.android.weights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.weights.AnimalForWeighing
import com.agrotech.oviapp.weights.Decimals
import com.agrotech.oviapp.weights.RecordWeightResult
import com.agrotech.oviapp.weights.WeightError
import com.agrotech.oviapp.weights.WeightInput
import com.agrotech.oviapp.weights.WeightMilestone
import com.agrotech.oviapp.weights.WeightRules
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Formulario de pesaje (APP-15). La ficha del animal (APP-05) lo muestra y le pasa [onSave], que
 * normalmente es `RecordWeightUseCase::record`; todo se valida y se guarda localmente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeightCaptureForm(
    animal: AnimalForWeighing,
    onSave: (WeightInput) -> RecordWeightResult,
    modifier: Modifier = Modifier,
) {
    var milestone by remember { mutableStateOf(WeightMilestone.EVALUATION) }
    var weight by remember { mutableStateOf("") }
    var scrotal by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()) }
    var result by remember { mutableStateOf<RecordWeightResult?>(null) }
    var invalidDate by remember { mutableStateOf(false) }
    val showScrotal = WeightRules.scrotalCircumferenceAvailable(animal.sex)
    val errors = (result as? RecordWeightResult.Rejected)?.errors.orEmpty()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hito", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightMilestone.entries.forEach { option ->
                FilterChip(
                    selected = option == milestone,
                    onClick = { milestone = option },
                    label = { Text(option.label()) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }

        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it },
            label = { Text("Peso (kg)") },
            isError = errors.any { it == WeightError.INVALID_WEIGHT_KG || it == WeightError.INVALID_WEIGHT_PRECISION },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Circunferencia escrotal solo existe para machos; en hembras el campo ni se muestra.
        if (showScrotal) {
            OutlinedTextField(
                value = scrotal,
                onValueChange = { scrotal = it },
                label = { Text("Circunferencia escrotal (cm, opcional)") },
                isError = errors.any { it == WeightError.INVALID_SCROTAL_CIRCUMFERENCE || it == WeightError.INVALID_SCROTAL_PRECISION },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = dateText,
            onValueChange = { dateText = it },
            label = { Text("Fecha (AAAA-MM-DD)") },
            isError = invalidDate || WeightError.WEIGHT_DATE_BEFORE_BIRTH in errors,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                invalidDate = date == null
                if (date != null) {
                    result = onSave(
                        WeightInput(
                            milestone = milestone,
                            weightKg = weight,
                            scrotalCircumferenceCm = scrotal.takeIf { showScrotal },
                            date = date,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Guardar pesaje") }

        if (invalidDate) Text("Escribe la fecha como 2026-03-02.", color = MaterialTheme.colorScheme.error)
        errors.forEach { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        (result as? RecordWeightResult.Recorded)?.let { recorded ->
            val entry = recorded.entry
            Text("Guardado. Edad: ${entry.ageDays} días.")
            Text(
                entry.dailyGainGrams?.let { grams ->
                    "GDP: $grams g/día (${Decimals.format(entry.dailyGainKgTenThousandths!!, scale = 4)} kg/día) " +
                        "en ${entry.daysSincePrevious} días."
                } ?: "GDP: se calcula a partir del siguiente pesaje.",
            )
        }
        if (result == RecordWeightResult.AnimalNotFound) {
            Text("El animal ya no está en este celular.", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun WeightMilestone.label(): String = when (this) {
    WeightMilestone.BIRTH -> "Nacimiento"
    WeightMilestone.WEANING -> "Destete"
    WeightMilestone.DAY_120 -> "120 días"
    WeightMilestone.DAY_150 -> "150 días"
    WeightMilestone.EVALUATION -> "Evaluación"
}

private fun WeightError.message(): String = when (this) {
    WeightError.INVALID_WEIGHT_KG -> "El peso debe ser mayor a 0 y hasta 999.99 kg."
    WeightError.INVALID_WEIGHT_PRECISION -> "El peso admite máximo 2 decimales."
    WeightError.INVALID_SCROTAL_CIRCUMFERENCE -> "La circunferencia debe ser mayor a 0 y hasta 99.99 cm."
    WeightError.INVALID_SCROTAL_PRECISION -> "La circunferencia admite máximo 2 decimales."
    WeightError.SCROTAL_FEMALE_NOT_ALLOWED -> "La circunferencia escrotal solo se registra en machos."
    WeightError.WEIGHT_DATE_BEFORE_BIRTH -> "La fecha no puede ser anterior al nacimiento."
    WeightError.ANIMAL_DECEASED -> "No se pueden registrar pesajes de un animal muerto."
}
