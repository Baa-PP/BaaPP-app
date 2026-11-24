package com.agrotech.oviapp.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Carreras entre el registro del token y onLogout, con un planificador de prueba: el store pausa al
 * registrador justo en el punto crítico, ahí se ejecuta lo que compite (`runCurrent`) y después se
 * deja terminar todo. Sin esperas por tiempo: el orden es siempre el mismo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTokenLogoutRaceTest {

    @Test
    fun aLogoutCannotSlipBetweenTheCheckAndTheWrite() = runTest {
        val store = CheckpointStore()
        val registrar = DeviceTokenRegistrar(OkApi, store)
        registrar.onNewToken("token-1")
        // Ya pasó la revisión de generación y va a escribir: el logout intenta correr justo ahí.
        store.beforeWrite = { runNow { DeviceTokenRegistrar(OkApi, store).onLogout() } }

        registrar.registerIfNeeded(accountId = "user-a")
        testScheduler.advanceUntilIdle()

        assertEquals(1, store.sessionGeneration)
        assertNull(store.registeredToken, "el token del usuario anterior no debe quedar marcado tras el logout")
    }

    @Test
    fun theNextUserIsNotSkippedWhileThePreviousLogoutIsPending() = runTest {
        val store = CheckpointStore()
        val api = CountingApi()
        DeviceTokenRegistrar(api, store).onNewToken("token-1")
        var nextUser: DeviceTokenRegistration? = null
        // El usuario anterior acaba de marcar su registro y aún tiene el candado: llega su logout y,
        // enseguida, el registro del siguiente usuario.
        store.afterWrite = {
            runNow {
                launch { DeviceTokenRegistrar(api, store).onLogout() }
                // Misma cuenta a propósito: la marca coincidiría, así que solo el orden del candado la salva.
                launch { nextUser = DeviceTokenRegistrar(api, store).registerIfNeeded(accountId = "user-a") }
            }
        }

        val previousUser = async { DeviceTokenRegistrar(api, store).registerIfNeeded(accountId = "user-a") }
        testScheduler.advanceUntilIdle()

        assertIs<DeviceTokenRegistration.Registered>(previousUser.await())
        // Si leyera la marca antes de que el logout la borre, diría AlreadyRegistered y no mandaría nada.
        assertIs<DeviceTokenRegistration.Registered>(nextUser)
        assertEquals(2, api.calls)
        assertEquals("token-1", store.registeredToken)
    }

    /** Arranca [block] en el mismo planificador y corre ahora todo lo que pueda avanzar sin esperar al candado. */
    private fun TestScope.runNow(block: suspend TestScope.() -> Unit) {
        launch { block() }
        testScheduler.runCurrent()
    }

    private object OkApi : DeviceTokenApi {
        override suspend fun register(request: DeviceTokenRequest) = Unit
    }

    private class CountingApi : DeviceTokenApi {
        var calls = 0
        override suspend fun register(request: DeviceTokenRequest) {
            calls++
        }
    }

    private class CheckpointStore : DeviceTokenStore {
        var beforeWrite: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null

        override var pendingToken: String? = null
        override var registeredAccount: String? = null
        override var sessionGeneration: Long = 0
        private var registered: String? = null

        override var registeredToken: String?
            get() = registered
            set(value) {
                if (value != null) beforeWrite?.also { beforeWrite = null }?.invoke()
                registered = value
                if (value != null) afterWrite?.also { afterWrite = null }?.invoke()
            }
    }
}
