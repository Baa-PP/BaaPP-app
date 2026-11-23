package com.agrotech.oviapp.android.ui

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.summary.RanchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RanchSummaryLoaderTest {
    private var calls = 0
    private val summary = RanchSummary.EMPTY.copy(activeAnimals = 9)
    private val loader: suspend (String) -> RanchSummary = { calls++; summary }

    private val owner = AuthSession(accessToken = "t", username = "dueno", role = "OWNER", ranchId = "r1")
    private val workerWithRead = AuthSession(
        accessToken = "t", username = "encargado", role = "WORKER_BRANCH", ranchId = "r1",
        permissions = mapOf("lectura" to true),
    )
    private val workerWithoutRead = workerWithRead.copy(permissions = mapOf("captura" to true))

    @Test
    fun loadsTheSummaryForASessionThatCanReadTheInventory() = runBlocking<Unit> {
        assertEquals(RanchSummaryUi.Ready(summary), loadRanchSummary(owner, loader))
        assertEquals(RanchSummaryUi.Ready(summary), loadRanchSummary(workerWithRead, loader))
        assertEquals(2, calls)
    }

    @Test
    fun withoutReadPermissionNothingIsLoaded() = runBlocking<Unit> {
        // Igual que el inventario, que muestra "Sin permiso" a quien no tiene Lectura.
        assertEquals(RanchSummaryUi.NoPermission, loadRanchSummary(workerWithoutRead, loader))
        assertEquals(0, calls)
    }

    @Test
    fun aMissingOrBlankRanchIsUnassigned() = runBlocking<Unit> {
        assertEquals(RanchSummaryUi.Unassigned, loadRanchSummary(owner.copy(ranchId = null), loader))
        assertEquals(RanchSummaryUi.Unassigned, loadRanchSummary(owner.copy(ranchId = "  "), loader))
        assertEquals(0, calls)
    }

    @Test
    fun aDatabaseFailureIsReportedAsALoadErrorNotAsAMissingRanch() = runBlocking<Unit> {
        val failing: suspend (String) -> RanchSummary = { error("disco lleno") }

        assertIs<RanchSummaryUi.LoadError>(loadRanchSummary(owner, failing))
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val cancelled: suspend (String) -> RanchSummary = { throw CancellationException("salió de la pantalla") }

        assertFailsWith<CancellationException> { runBlocking { loadRanchSummary(owner, cancelled) } }
    }
}
