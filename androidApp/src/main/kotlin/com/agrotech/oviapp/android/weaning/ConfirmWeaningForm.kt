package com.agrotech.oviapp.android.weaning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.weaning.ConfirmWeaningInput
import com.agrotech.oviapp.weaning.ConfirmWeaningResult
import com.agrotech.oviapp.weaning.WeaningDestination
import com.agrotech.oviapp.weaning.WeaningError
import com.agrotech.oviapp.weights.WeightError
import com.agrotech.oviapp.weights.WeightRules
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Formulario "Confirmar destete" (APP-18). Se abre desde la lista de destetes (APP-16) o la ficha de
 * la cría; [onConfirm] normalmente es `ConfirmWeaningUseCase.confirm(animalId, ...)`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmWeaningForm(
    animalId: String,
    folioInterno: String,
    sex: Sex,
    onConfirm: (ConfirmWeaningInput) -> ConfirmWeaningResult,
    modifier: Modifier = Modifier,
) {
    val destinations = WeaningDestination.optionsFor(sex)
    var destination by rememberSaveable(animalId) { mutableStateOf(WeaningDestination.LAMB_FATTENING) }
    var weight by rememberSaveable(animalId) { mutableStateOf("") }
    var scrotal by rememberSaveable(animalId) { mutableStateOf("") }
    var dateText by rememberSaveable(animalId) {
        mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault()).toString())
    }
    var invalidDate by rememberSaveable(animalId) { mutableStateOf(false) }
    var result by remember(animalId) { mutableStateOf<ConfirmWeaningResult?>(null) }
    val rejected = result as? ConfirmWeaningResult.Rejected
    val showScrotal = WeightRules.scrotalCircumferenceAvailable(sex)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Destetar $folioInterno", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it },
            label = { Text("Peso al destete (kg)") },
            isError = rejected?.weightErrors?.any {
                it == WeightError.INVALID_WEIGHT_KG || it == WeightError.INVALID_WEIGHT_PRECISION
            } == true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showScrotal) {
            OutlinedTextField(
                value = scrotal,
                onValueChange = { scrotal = it },
                label = { Text("Circunferencia escrotal (cm, opcional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = dateText,
            onValueChange = { dateText = it },
            label = { Text("Fecha de destete (AAAA-MM-DD)") },
            isError = invalidDate,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Destino", style = MaterialTheme.typography.titleMedium)
        // Los machos no ven "Recría": el backend lo rechaza (INVALID_WEANING_DESTINATION).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destinations.forEach { option ->
                FilterChip(
                    selected = option == destination,
                    onClick = { destination = option },
                    label = { Text(option.label()) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }

        Button(
            onClick = {
                val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                invalidDate = date == null
                if (date != null) {
                    result = onConfirm(
                        ConfirmWeaningInput(
                            weaningDate = date,
                            weaningWeightKg = weight,
                            destination = destination,
                            scrotalCircumferenceCm = scrotal.takeIf { showScrotal },
                        ),
                    )
                }
            },
            enabled = result !is ConfirmWeaningResult.Confirmed,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Confirmar destete") }

        if (invalidDate) Text("Escribe la fecha como 2026-11-22.", color = MaterialTheme.colorScheme.error)
        rejected?.weaningErrors?.forEach { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        rejected?.weightErrors?.forEach { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        when (val done = result) {
            is ConfirmWeaningResult.Confirmed -> {
                Text("Destete registrado. Se enviará al servidor cuando haya señal.")
                if (done.motherTransitionedToEmpty) Text("La madre pasó a Vacía.")
            }
            ConfirmWeaningResult.AnimalNotFound ->
                Text("La cría ya no está en este celular.", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

private fun WeaningDestination.label(): String = when (this) {
    WeaningDestination.LAMB_FATTENING -> "Engorda"
    WeaningDestination.EWE_REPLACEMENT -> "Recría / Reemplazo"
    WeaningDestination.SOLD -> "Venta"
}

private fun WeaningError.message(): String = when (this) {
    WeaningError.ANIMAL_ALREADY_WEANED -> "Esta cría ya fue destetada."
    WeaningError.ANIMAL_NOT_ACTIVE -> "Solo se pueden destetar animales activos."
    WeaningError.INVALID_WEANING_DESTINATION -> "Un macho no puede ir a recría de hembra."
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
