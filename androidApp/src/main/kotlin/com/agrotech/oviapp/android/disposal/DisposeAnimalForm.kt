package com.agrotech.oviapp.android.disposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.disposal.DisposalError
import com.agrotech.oviapp.disposal.DisposalResult
import com.agrotech.oviapp.disposal.MortalityInput
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private enum class DisposalKind { DEATH, SALE }

/**
 * "Dar de baja" (APP-19) desde la ficha del animal (APP-08). No existe opción de borrar: la baja solo
 * cambia el estado y el animal se conserva en el historial. [onDeath]/[onSale] normalmente son
 * `DisposeAnimalUseCase.recordDeath(animalId, ...)` y `recordSale(animalId)`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisposeAnimalForm(
    animalId: String,
    folioInterno: String,
    sex: Sex,
    currentLifeStage: LifeStage,
    onDeath: (MortalityInput) -> DisposalResult,
    onSale: () -> DisposalResult,
    modifier: Modifier = Modifier,
) {
    var kind by rememberSaveable(animalId) { mutableStateOf(DisposalKind.DEATH) }
    var dateText by rememberSaveable(animalId) {
        mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault()).toString())
    }
    var cause by rememberSaveable(animalId) { mutableStateOf("") }
    var stage by rememberSaveable(animalId) { mutableStateOf(currentLifeStage) }
    var invalidDate by rememberSaveable(animalId) { mutableStateOf(false) }
    var result by remember(animalId) { mutableStateOf<DisposalResult?>(null) }
    val errors = (result as? DisposalResult.Rejected)?.errors.orEmpty()
    val done = result == DisposalResult.Done
    // Un macho no puede tener etapa EWE_REPLACEMENT (mismo criterio que el backend).
    val stages = LifeStage.entries.filter { sex == Sex.FEMALE || it != LifeStage.EWE_REPLACEMENT }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dar de baja $folioInterno", style = MaterialTheme.typography.titleLarge)
        Text("El animal no se borra: sale de las listas y se conserva en el historial de su madre.")

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = kind == DisposalKind.DEATH,
                onClick = { kind = DisposalKind.DEATH },
                label = { Text("Muerte") },
                modifier = Modifier.heightIn(min = 56.dp),
            )
            FilterChip(
                selected = kind == DisposalKind.SALE,
                onClick = { kind = DisposalKind.SALE },
                label = { Text("Venta") },
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }

        if (kind == DisposalKind.DEATH) {
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Fecha de muerte (AAAA-MM-DD)") },
                isError = invalidDate || DisposalError.INVALID_MORTALITY_DATE in errors,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cause,
                onValueChange = { cause = it },
                label = { Text("Causa de muerte") },
                isError = DisposalError.MORTALITY_INFO_REQUIRED in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Etapa al morir", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stages.forEach { option ->
                    FilterChip(
                        selected = option == stage,
                        onClick = { stage = option },
                        label = { Text(option.label()) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }

        Button(
            onClick = {
                result = when (kind) {
                    DisposalKind.SALE -> onSale()
                    DisposalKind.DEATH -> {
                        val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                        invalidDate = date == null
                        date?.let { onDeath(MortalityInput(it, cause, stage)) }
                    }
                }
            },
            enabled = !done,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (kind == DisposalKind.DEATH) "Registrar muerte" else "Registrar venta") }

        if (invalidDate) Text("Escribe la fecha como 2026-11-22.", color = MaterialTheme.colorScheme.error)
        errors.forEach { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        when (result) {
            DisposalResult.Done -> Text("Baja registrada. Se enviará al servidor cuando haya señal.")
            DisposalResult.AnimalNotFound ->
                Text("El animal ya no está en este celular.", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

private fun LifeStage.label(): String = when (this) {
    LifeStage.LAMB_LACTATING -> "Cordero lactante"
    LifeStage.LAMB_FATTENING -> "Engorda"
    LifeStage.EWE_REPLACEMENT -> "Reemplazo"
    LifeStage.ADULT -> "Adulto"
}

private fun DisposalError.message(): String = when (this) {
    DisposalError.MORTALITY_INFO_REQUIRED -> "Escribe la causa de muerte."
    DisposalError.INVALID_MORTALITY_DATE -> "La fecha de muerte no puede ser anterior al nacimiento."
    DisposalError.INVALID_LIFE_STAGE -> "Un macho no puede tener etapa de reemplazo."
    DisposalError.ANIMAL_NOT_ACTIVE -> "Este animal ya fue dado de baja."
}
