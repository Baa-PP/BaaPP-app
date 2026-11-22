package com.agrotech.oviapp.android.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.auth.domain.AuthSession

private val BaappPrimaryGreen = Color(0xFF1B5E20)
private val BaappBackground = Color(0xFFF7F9F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: AuthSession,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val friendlyRole = when (session.normalizedRole) {
        "OWNER" -> "Dueño / Propietario"
        "WORKER_BRANCH" -> "Encargado de Corral"
        "VET_TECHNICIAN" -> "Médico Veterinario / Técnico"
        "ASSOCIATION_SUPERUSER" -> "Asociación Ganadera"
        else -> session.role
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
                            text = "Rancho: ${session.ranchId ?: "Sin rancho asignado"}",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFE8F5E9))
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
            // Status Banner (Offline-First Ready)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(0xFF2E7D32), shape = RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Sesión activa · Captura offline lista",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                        )
                        Text(
                            text = "Usuario: ${session.username} · Rol: $friendlyRole",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF2E7D32))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Módulos de Corral (MVP 2026)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(12.dp))

            ModuleCard(
                title = "Inventario del Rebaño",
                subtitle = "Hembras (vientres), Sementales y Crías",
                badge = "Próximo (APP-06)"
            )

            Spacer(modifier = Modifier.height(12.dp))

            ModuleCard(
                title = "Registro de Partos con Foto",
                subtitle = "Regla anti-fraude de 30 días y deducción de crías",
                badge = "Próximo (APP-12)"
            )

            Spacer(modifier = Modifier.height(12.dp))

            ModuleCard(
                title = "Pesajes y Ganancia Diaria (GDP)",
                subtitle = "Circunferencia escrotal, destetes a 75 días",
                badge = "Próximo (APP-15)"
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onLogoutClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cerrar Sesión Segura", color = Color(0xFFB71C1C))
            }
        }
    }
}

@Composable
private fun TextButtonAction(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    badge: String
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
                        .background(Color(0xFFE8F5E9), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
            )
        }
    }
}
