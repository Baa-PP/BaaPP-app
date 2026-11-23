package com.agrotech.oviapp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.summary.RanchSummary
import kotlinx.coroutines.CancellationException

private val SummaryInk = Color(0xFF111111)
private val SummaryPaper = Color(0xFFFFFFFF)
private val SummaryAlertBg = Color(0xFFFFF8E1)
private val SummaryAlertInk = Color(0xFF5D4300)

/** Estado de las tarjetas del resumen (APP-25). */
sealed interface RanchSummaryUi {
    data object Loading : RanchSummaryUi
    data class Ready(val summary: RanchSummary) : RanchSummaryUi

    /** Sin permiso de Lectura: igual que el inventario, no se muestran conteos del rancho. */
    data object NoPermission : RanchSummaryUi

    /** La sesión no tiene rancho asignado (o viene vacío). */
    data object Unassigned : RanchSummaryUi

    /** Falló la lectura de la base local; no es culpa de la sesión. */
    data object LoadError : RanchSummaryUi
}

/**
 * Decide qué mostrar en el resumen: exige el mismo permiso que el inventario y un rancho asignado antes
 * de leer la base, y distingue un fallo de lectura de una sesión sin rancho.
 */
internal suspend fun loadRanchSummary(
    session: AuthSession,
    loadSummary: suspend (ranchId: String) -> RanchSummary,
): RanchSummaryUi {
    if (!session.uiCapabilities().canReadInventory) return RanchSummaryUi.NoPermission
    val ranchId = session.ranchId
    if (ranchId.isNullOrBlank()) return RanchSummaryUi.Unassigned
    return try {
        RanchSummaryUi.Ready(loadSummary(ranchId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RanchSummaryUi.LoadError
    }
}

/**
 * Tarjetas del resumen del rancho, calculadas desde la base local: se ven igual con o sin señal. Cada
 * número coincide con la lista correspondiente del inventario.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RanchSummarySection(state: RanchSummaryUi, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = "Resumen del rancho",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        when (state) {
            RanchSummaryUi.Loading -> Text("Calculando…", modifier = Modifier.padding(top = 8.dp))
            RanchSummaryUi.NoPermission -> Text(
                "Sin permiso de lectura para ver los conteos del rancho.",
                modifier = Modifier.padding(top = 8.dp),
            )
            RanchSummaryUi.Unassigned -> Text(
                "El resumen se muestra cuando la sesión tiene un rancho asignado.",
                modifier = Modifier.padding(top = 8.dp),
            )
            RanchSummaryUi.LoadError -> Text(
                "No se pudieron leer los datos guardados en este celular. Intenta de nuevo al volver a esta pantalla.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            is RanchSummaryUi.Ready -> {
                val s = state.summary
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SummaryCard("Animales activos", s.activeAnimals)
                    SummaryCard(
                        "Vientres",
                        s.ewes,
                        "Gestantes ${s.pregnantEwes} · Lactando ${s.lactatingEwes} · Vacías ${s.emptyEwes}",
                    )
                    SummaryCard("Sementales", s.rams)
                    SummaryCard(
                        "Crías",
                        s.lambs,
                        "Lactancia ${s.lactatingLambs} · Engorda ${s.fatteningLambs} · Reemplazo ${s.replacementLambs}",
                    )
                    SummaryCard("Destetes pendientes", s.pendingWeanings, highlight = s.pendingWeanings > 0)
                    SummaryCard("Mortalidad del mes", s.deathsThisMonth, highlight = s.deathsThisMonth > 0)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, count: Long, detail: String? = null, highlight: Boolean = false) {
    val ink = if (highlight) SummaryAlertInk else SummaryInk
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlight) SummaryAlertBg else SummaryPaper),
        modifier = Modifier
            .widthIn(min = 150.dp)
            .heightIn(min = 56.dp)
            // Un lector de pantalla lee "Vientres: 4. Gestantes 2…" en lugar de números sueltos.
            .semantics { contentDescription = listOfNotNull("$label: $count", detail).joinToString(". ") },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = ink))
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = ink))
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall.copy(color = ink))
            }
        }
    }
}
