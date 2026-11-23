package com.agrotech.oviapp.android.weaning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.weaning.WeaningCandidate
import com.agrotech.oviapp.weaning.WeaningInfo
import com.agrotech.oviapp.weaning.WeaningList
import com.agrotech.oviapp.weaning.WeaningStatus

/**
 * Pantalla "Destetes" (APP-16). Recibe la lista de `PendingWeaningsUseCase.list(...)`, que se
 * calcula solo con SQLite; [onOpenAnimal] lleva a la ficha del animal (APP-05).
 */
@Composable
fun PendingWeaningsScreen(
    list: WeaningList,
    onOpenAnimal: (animalId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        section("Pendientes (${list.pending.size})", list.pending, "No hay destetes pendientes.", onOpenAnimal)
        section("Próximos (${list.upcoming.size})", list.upcoming, "Nada en los próximos días.", onOpenAnimal)
    }
}

private fun LazyListScope.section(
    title: String,
    candidates: List<WeaningCandidate>,
    emptyText: String,
    onOpenAnimal: (String) -> Unit,
) {
    item {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    }
    if (candidates.isEmpty()) {
        item { Text(emptyText) }
    }
    items(candidates, key = { it.animalId }) { candidate ->
        Card(
            onClick = { onOpenAnimal(candidate.animalId) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${candidate.folioInterno} · ${if (candidate.sex == Sex.MALE) "Macho" else "Hembra"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Destete: ${candidate.scheduledDate} · ${candidate.ageDays} días de edad")
                Text(
                    dueText(candidate.daysUntilWeaning),
                    color = if (candidate.daysUntilWeaning < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Línea para la ficha de la cría: fecha programada y cuánto falta o cuánto se atrasó. */
@Composable
fun ScheduledWeaningLabel(info: WeaningInfo, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Destete programado: ${info.scheduledDate}", style = MaterialTheme.typography.titleMedium)
        Text(
            when (info.status) {
                WeaningStatus.WEANED -> "Ya destetada."
                else -> dueText(info.daysUntilWeaning)
            },
            color = if (info.status == WeaningStatus.PENDING && info.daysUntilWeaning < 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun dueText(daysUntil: Int): String = when {
    daysUntil == 0 -> "Le toca hoy."
    daysUntil == 1 -> "Le toca mañana."
    daysUntil > 1 -> "Le toca en $daysUntil días."
    daysUntil == -1 -> "Atrasado 1 día."
    else -> "Atrasado ${-daysUntil} días."
}
