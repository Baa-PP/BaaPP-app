package com.agrotech.oviapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.RanchPermission
import com.agrotech.oviapp.auth.domain.UserRole
import com.agrotech.oviapp.births.domain.model.BirthImmutabilityPolicy
import com.agrotech.oviapp.summary.RanchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val BaappPrimaryGreen = Color(0xFF1B5E20)
private val BaappBackground = Color(0xFFF7F9F7)
private val BaappSoftGreen = Color(0xFFE8F5E9)
private val BaappMutedGray = Color(0xFFECEFF1)
private val BaappWarningBg = Color(0xFFFFF8E1)
private val BaappWarningText = Color(0xFF8D6E00)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    session: AuthSession,
    onLogoutClick: () -> Unit,
    onOpenInventoryClick: () -> Unit = {},
    onOpenRegisterAnimalClick: () -> Unit = {},
    onOpenBirthEventClick: () -> Unit = {},
    onOpenSavedBirthsClick: () -> Unit = {},
    loadSummary: (suspend (ranchId: String) -> RanchSummary)? = null,
    pendingSyncCount: Long = 0L,
    isSyncing: Boolean = false,
    syncFeedback: String? = null,
    onSyncFeedbackShown: () -> Unit = {},
    onSyncNowClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val capabilities = remember(session) { session.uiCapabilities() }

    LaunchedEffect(syncFeedback) {
        val message = syncFeedback ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
        onSyncFeedbackShown()
    }

    val showUpcomingNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val friendlyRole = when (capabilities.role) {
        UserRole.OWNER -> "Dueño / Propietario"
        UserRole.WORKER_BRANCH -> "Encargado de Corral"
        UserRole.VET_TECHNICIAN -> "Médico Veterinario / Técnico"
        UserRole.ASSOCIATION_SUPERUSER -> "Asociación Ganadera"
    }

    val scopeSubtitle = if (capabilities.isMultiRanchScope) {
        "Alcance: Multi-Rancho (${session.ranchId ?: "Asociación"})"
    } else {
        "Rancho: ${session.ranchId ?: "Sin rancho asignado"}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "BaaPP (OviApp)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = scopeSubtitle,
                            style = MaterialTheme.typography.bodySmall.copy(color = BaappSoftGreen)
                        )
                    }
                },
                actions = {
                    TextButtonAction(text = "Salir", onClick = onLogoutClick)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BaappPrimaryGreen,
                    titleContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BaappBackground)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Status Banner (Offline-First Ready + Role Context)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BaappSoftGreen)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(0xFF2E7D32), shape = RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    capabilities.canCapture -> "Sesión activa · Captura offline lista"
                                    capabilities.isReadOnlyMode -> "Sesión activa · Modo consulta"
                                    else -> "Sesión activa · Edición habilitada"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = BaappPrimaryGreen
                                )
                            )
                            Text(
                                text = "Usuario: ${session.username} · Rol: $friendlyRole",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF2E7D32))
                            )
                        }
                    }

                    if (capabilities.role == UserRole.WORKER_BRANCH) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Permisos asignados:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = BaappPrimaryGreen
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PermissionBadge("Lectura", session.hasPermission(RanchPermission.LECTURA))
                            PermissionBadge("Captura", session.hasPermission(RanchPermission.CAPTURA))
                            PermissionBadge("Edición", session.hasPermission(RanchPermission.EDICION))
                            PermissionBadge("Exportación", session.hasPermission(RanchPermission.EXPORTACION))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (pendingSyncCount == 0L) {
                            "Sincronización: Todo sincronizado"
                        } else {
                            "Sincronización: $pendingSyncCount cambio(s) pendiente(s)"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = BaappPrimaryGreen
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSyncNowClick,
                        enabled = !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                    ) {
                        Text(if (isSyncing) "Sincronizando..." else "Sincronizar ahora")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Resumen del rancho (APP-25): se recalcula cada vez que se vuelve a la pantalla principal.
            if (loadSummary != null) {
                var summaryState by remember(session) { mutableStateOf<RanchSummaryUi>(RanchSummaryUi.Loading) }
                LaunchedEffect(session) { summaryState = loadRanchSummary(session, loadSummary) }
                RanchSummarySection(summaryState)
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = "Módulos de Corral (MVP 2026)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Inventario del Rebaño
            ModuleCard(
                title = "Inventario del Rebaño",
                subtitle = "Hembras (vientres), Sementales y Crías",
                badge = "APP-06"
            ) {
                if (!capabilities.canReadInventory && !capabilities.canCapture && !capabilities.canEditGeneralRecords) {
                    RestrictedBanner("Sin permiso para consultar, capturar o editar en el inventario.")
                } else {
                    if (capabilities.canReadInventory || capabilities.canCapture) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (capabilities.canReadInventory) {
                                OutlinedButton(
                                    onClick = {
                                        if (session.ranchId.isNullOrBlank()) {
                                            showUpcomingNotice("No tienes un rancho asignado para consultar inventario.")
                                        } else {
                                            onOpenInventoryClick()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Consultar")
                                }
                            }

                            if (capabilities.canCapture) {
                                Button(
                                    onClick = {
                                        if (session.ranchId.isNullOrBlank()) {
                                            showUpcomingNotice("No tienes un rancho asignado para registrar ejemplares.")
                                        } else {
                                            onOpenRegisterAnimalClick()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                                ) {
                                    Text("Alta de Ejemplar")
                                }
                            }
                        }
                    }

                    if (capabilities.canEditGeneralRecords && capabilities.canReadInventory) {
                        if (capabilities.canReadInventory || capabilities.canCapture) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                if (session.ranchId.isNullOrBlank()) {
                                    showUpcomingNotice("No tienes un rancho asignado para editar ejemplares.")
                                } else {
                                    onOpenInventoryClick()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Editar Ficha / Reponer Arete")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Registro de Partos con Foto
            ModuleCard(
                title = "Registro de Partos con Foto",
                subtitle = "Regla anti-fraude de 30 días y deducción de crías",
                badge = "APP-12"
            ) {
                if (!capabilities.canReadInventory && !capabilities.canCapture) {
                    RestrictedBanner("Sin permisos para consultar o registrar nacimientos.")
                } else {
                    if (capabilities.canCapture) {
                        Button(
                            onClick = {
                                if (session.ranchId.isNullOrBlank()) {
                                    showUpcomingNotice("No tienes un rancho asignado para registrar nacimientos.")
                                } else {
                                    onOpenBirthEventClick()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                        ) {
                            Text("Registrar Parto (≤ 30 días)")
                        }
                    }

                    if (capabilities.canReadInventory) {
                        if (capabilities.canCapture) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                if (session.ranchId.isNullOrBlank()) {
                                    showUpcomingNotice("No tienes un rancho asignado para consultar nacimientos.")
                                } else {
                                    onOpenSavedBirthsClick()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Ver Nacimientos Guardados")
                        }
                    }

                    if (capabilities.canEditSavedBirths) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showUpcomingNotice("Corrección auditada de parto disponible para Técnico") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Corregir / Auditar Nacimiento Guardado")
                        }
                    } else if (capabilities.showBirthImmutabilityNotice) {
                        if (capabilities.canCapture || capabilities.canReadInventory) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        InfoNoticeBanner(
                            BirthImmutabilityPolicy.AUDITED_CORRECTION_NOTICE
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Pesajes y Ganancia Diaria (GDP)
            ModuleCard(
                title = "Pesajes y Ganancia Diaria (GDP)",
                subtitle = "Circunferencia escrotal, destetes a 75 días",
                badge = "APP-15"
            ) {
                if (!capabilities.canReadInventory && !capabilities.canCapture) {
                    RestrictedBanner("Sin permisos para consultar o capturar pesajes.")
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (capabilities.canReadInventory) {
                            OutlinedButton(
                                onClick = { showUpcomingNotice("Historial de pesajes disponible en APP-15") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Ver Pesajes")
                            }
                        }

                        if (capabilities.canCapture) {
                            Button(
                                onClick = { showUpcomingNotice("Captura de pesaje disponible en APP-15") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                            ) {
                                Text("Capturar Pesaje")
                            }
                        }
                    }
                }
            }

            if (capabilities.canExport) {
                Spacer(modifier = Modifier.height(12.dp))

                ModuleCard(
                    title = "Exportación de Reportes",
                    subtitle = "Descarga de inventario e historial del rancho",
                    badge = "Permitido"
                ) {
                    OutlinedButton(
                        onClick = { showUpcomingNotice("Exportación de reportes disponible próximamente") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Exportar Datos del Rancho")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onLogoutClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cerrar Sesión Segura", color = Color(0xFFB71C1C))
            }
        }
    }
}

@Composable
private fun TextButtonAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionBadge(label: String, granted: Boolean) {
    val bg = if (granted) Color.White else BaappMutedGray
    val textColor = if (granted) BaappPrimaryGreen else Color.Gray
    val prefix = if (granted) "✓ " else "✕ "
    Text(
        text = "$prefix$label",
        style = MaterialTheme.typography.labelSmall.copy(
            color = textColor,
            fontWeight = if (granted) FontWeight.SemiBold else FontWeight.Normal
        ),
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun RestrictedBanner(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF616161)),
        modifier = Modifier
            .fillMaxWidth()
            .background(BaappMutedGray, shape = RoundedCornerShape(8.dp))
            .padding(10.dp)
    )
}

@Composable
private fun InfoNoticeBanner(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall.copy(color = BaappWarningText),
        modifier = Modifier
            .fillMaxWidth()
            .background(BaappWarningBg, shape = RoundedCornerShape(8.dp))
            .padding(10.dp)
    )
}

@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    badge: String,
    content: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF2E7D32)),
                    modifier = Modifier
                        .background(BaappSoftGreen, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
