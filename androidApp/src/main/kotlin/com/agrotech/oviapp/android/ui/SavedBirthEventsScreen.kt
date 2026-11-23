package com.agrotech.oviapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.auth.domain.UiAccessCapabilities
import com.agrotech.oviapp.births.domain.model.BirthImmutabilityPolicy
import com.agrotech.oviapp.births.domain.model.SavedBirthEvent
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import kotlinx.coroutines.CancellationException

private val SavedBirthPrimaryGreen = Color(0xFF1B5E20)
private val SavedBirthBackground = Color(0xFFF7F9F7)
private val SavedBirthSoftGreen = Color(0xFFE8F5E9)
private val SavedBirthWarningBg = Color(0xFFFFF8E1)
private val SavedBirthWarningText = Color(0xFF8D6E00)

/**
 * Read-only view of saved birth events (APP-13 / RF-AND-04).
 *
 * Neither OWNER nor WORKER_BRANCH can edit offspring quantities or delete a saved birth.
 * All saved birth records are strictly read-only and display the audited correction notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedBirthEventsScreen(
    ranchId: String,
    capabilities: UiAccessCapabilities,
    birthEventRepository: BirthEventRepository,
    onNavigateBack: () -> Unit
) {
    var births by remember { mutableStateOf<List<SavedBirthEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }

    BackHandler { onNavigateBack() }

    LaunchedEffect(ranchId, retryCounter) {
        isLoading = true
        errorMessage = null
        try {
            births = birthEventRepository.getSavedBirthEvents(ranchId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "No se pudieron cargar los nacimientos guardados."
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nacimientos Guardados", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("← Volver", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SavedBirthPrimaryGreen,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = SavedBirthBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (capabilities.showBirthImmutabilityNotice || !capabilities.canEditSavedBirths) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SavedBirthWarningBg, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = BirthImmutabilityPolicy.AUDITED_CORRECTION_NOTICE,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SavedBirthWarningText,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SavedBirthPrimaryGreen)
                    }
                }
                errorMessage != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFB71C1C))
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { retryCounter += 1 },
                                colors = ButtonDefaults.buttonColors(containerColor = SavedBirthPrimaryGreen)
                            ) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
                births.isEmpty() -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Text(
                            text = "Aún no hay nacimientos guardados localmente en este rancho.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(births, key = { it.id }) { birth ->
                            SavedBirthEventCard(birth = birth)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedBirthEventCard(birth: SavedBirthEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fecha: ${birth.birthDateIso}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Box(
                    modifier = Modifier
                        .background(SavedBirthSoftGreen, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = birth.parturitionType,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SavedBirthPrimaryGreen,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Madre: ${birth.damFolio ?: birth.damId}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (!birth.sireFolio.isNullOrBlank() || !birth.sireId.isNullOrBlank()) {
                Text(
                    text = "Padre: ${birth.sireFolio ?: birth.sireId}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.DarkGray)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Nacidos vivos: ${birth.liveBirths} · Nacidos muertos: ${birth.stillborns}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "Concepción: ${birth.conceptionMethod} · Evidencia: ${if (birth.hasPhoto) "Foto adjunta" else "Sin foto"}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
            )

            if (!birth.notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Notas: ${birth.notes}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.DarkGray)
                )
            }
        }
    }
}
