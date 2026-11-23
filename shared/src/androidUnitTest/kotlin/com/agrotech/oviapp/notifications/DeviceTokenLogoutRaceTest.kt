package com.agrotech.oviapp.notifications

import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * La revisión de la generación y la escritura de registeredToken deben ser atómicas frente a onLogout,
 * que corre en otro hilo (sesión en el hilo principal, registro en WorkManager).
 */
class DeviceTokenLogoutRaceTest {

    @Test
    fun aLogoutCannotSlipBetweenTheCheckAndTheWrite() {
        val store = LogoutAtWriteStore()
        val registrar = DeviceTokenRegistrar(OkApi, store)
        registrar.onNewToken("token-1")
        // Justo cuando ya pasó la revisión y va a escribir el token, otro hilo cierra sesión.
        store.onWrite = {
            val logout = thread { runBlocking { DeviceTokenRegistrar(OkApi, store).onLogout() } }
            logout.join(300) // sin candado el logout termina aquí, antes de la escritura
            store.logoutThread = logout
        }

        runBlocking { registrar.registerIfNeeded(isLoggedIn = true) }
        store.logoutThread?.join()

        assertEquals(1, store.sessionGeneration)
        assertNull(store.registeredToken, "el token del usuario anterior no debe quedar marcado tras el logout")
    }

    private object OkApi : DeviceTokenApi {
        override suspend fun register(request: DeviceTokenRequest) = Unit
    }

    private class LogoutAtWriteStore : DeviceTokenStore {
        var onWrite: (() -> Unit)? = null
        var logoutThread: Thread? = null

        @Volatile override var pendingToken: String? = null

        @Volatile override var sessionGeneration: Long = 0

        @Volatile private var registered: String? = null

        override var registeredToken: String?
            get() = registered
            set(value) {
                if (value != null) onWrite?.also { onWrite = null }?.invoke()
                registered = value
            }
    }
}
