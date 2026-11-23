package com.agrotech.oviapp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.animals.domain.model.ParentOption
import com.agrotech.oviapp.animals.domain.model.ParentSelectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSelectorsSection(
    state: ParentSelectionState,
    dams: List<ParentOption>,
    sires: List<ParentOption>,
    onStateChange: (ParentSelectionState) -> Unit,
    excludeAnimalId: String? = null,
    modifier: Modifier = Modifier
) {
    var damExpanded by remember { mutableStateOf(false) }
    var sireExpanded by remember { mutableStateOf(false) }

    val selectableDams = remember(dams, excludeAnimalId) {
        if (excludeAnimalId == null) dams else dams.filterNot { it.id == excludeAnimalId }
    }
    val selectableSires = remember(sires, excludeAnimalId) {
        if (excludeAnimalId == null) sires else sires.filterNot { it.id == excludeAnimalId }
    }

    val selectedDamLabel = dams.firstOrNull { it.id == state.damId }?.folioInterno
        ?: if (state.damId != null) "Madre registrada (no activa)" else "Ninguna / Madre externa"
    val selectedSireLabel = sires.firstOrNull { it.id == state.sireId }?.folioInterno
        ?: if (state.sireId != null) "Semental registrado (no activo)" else "Ninguno / Semental externo"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Genealogía (Progenitores)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            ExposedDropdownMenuBox(
                expanded = damExpanded,
                onExpandedChange = { damExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedDamLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Madre en Rebaño (Hembras)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = damExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                ExposedDropdownMenu(
                    expanded = damExpanded,
                    onDismissRequest = { damExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Ninguna / Madre externa") },
                        onClick = {
                            onStateChange(state.copy(damId = null))
                            damExpanded = false
                        }
                    )
                    selectableDams.forEach { dam ->
                        DropdownMenuItem(
                            text = { Text("${dam.folioInterno} (${dam.breed})") },
                            onClick = {
                                onStateChange(state.copy(damId = dam.id, externalDamFolio = ""))
                                damExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.externalDamFolio,
                onValueChange = { value ->
                    onStateChange(
                        state.copy(
                            damId = if (value.isNotBlank()) null else state.damId,
                            externalDamFolio = value
                        )
                    )
                },
                label = { Text("Folio Madre Externa (texto libre)") },
                placeholder = { Text("Ej. MADRE-EXT-01") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = sireExpanded,
                onExpandedChange = { sireExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedSireLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Semental en Rebaño (Machos)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sireExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                ExposedDropdownMenu(
                    expanded = sireExpanded,
                    onDismissRequest = { sireExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Ninguno / Semental externo") },
                        onClick = {
                            onStateChange(state.copy(sireId = null))
                            sireExpanded = false
                        }
                    )
                    selectableSires.forEach { sire ->
                        DropdownMenuItem(
                            text = { Text("${sire.folioInterno} (${sire.breed})") },
                            onClick = {
                                onStateChange(state.copy(sireId = sire.id, externalSireFolio = ""))
                                sireExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.externalSireFolio,
                onValueChange = { value ->
                    onStateChange(
                        state.copy(
                            sireId = if (value.isNotBlank()) null else state.sireId,
                            externalSireFolio = value
                        )
                    )
                },
                label = { Text("Folio Semental Externo (texto libre)") },
                placeholder = { Text("Ej. SEMENTAL-EXT-01") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
