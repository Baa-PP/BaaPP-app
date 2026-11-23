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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier
) {
    var damExpanded by remember { mutableStateOf(false) }
    var sireExpanded by remember { mutableStateOf(false) }

    val selectedDamLabel = dams.firstOrNull { it.id == state.damId }?.folioInterno
        ?: state.damId
        ?: "Ninguna / Madre externa"
    val selectedSireLabel = sires.firstOrNull { it.id == state.sireId }?.folioInterno
        ?: state.sireId
        ?: "Ninguno / Semental externo"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
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
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                    dams.forEach { dam ->
                        DropdownMenuItem(
                            text = { Text("${dam.folioInterno} (${dam.breed})") },
                            onClick = {
                                onStateChange(state.copy(damId = dam.id))
                                damExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.externalDamFolio,
                onValueChange = { onStateChange(state.copy(externalDamFolio = it)) },
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
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                    sires.forEach { sire ->
                        DropdownMenuItem(
                            text = { Text("${sire.folioInterno} (${sire.breed})") },
                            onClick = {
                                onStateChange(state.copy(sireId = sire.id))
                                sireExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.externalSireFolio,
                onValueChange = { onStateChange(state.copy(externalSireFolio = it)) },
                label = { Text("Folio Semental Externo (texto libre)") },
                placeholder = { Text("Ej. SEMENTAL-EXT-01") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
