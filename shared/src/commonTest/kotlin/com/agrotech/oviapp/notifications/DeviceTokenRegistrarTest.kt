package com.agrotech.oviapp.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceTokenRegistrarTest {

    @Test
    fun registersANewTokenOnceTheUserIsLoggedIn() = runTest {
        val api = FakeDeviceTokenApi()
        val store = InMemoryDeviceTokenStore()
        val registrar = DeviceTokenRegistrar(api, store)

        registrar.onNewToken("token-1")
        val result = registrar.registerIfNeeded(isLoggedIn = true)

        assertIs<DeviceTokenRegistration.Registered>(result)
        assertEquals(listOf(DeviceTokenRequest(token = "token-1", platform = "ANDROID")), api.sent)
        assertEquals("token-1", store.registeredToken)
    }

    @Test
    fun waitsForLoginBeforeSendingTheToken() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())

        registrar.onNewToken("token-1")

        assertIs<DeviceTokenRegistration.NotLoggedIn>(registrar.registerIfNeeded(isLoggedIn = false))
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun doesNotResendATokenThatIsAlreadyRegistered() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(isLoggedIn = true)

        val second = registrar.registerIfNeeded(isLoggedIn = true)

        assertIs<DeviceTokenRegistration.AlreadyRegistered>(second)
        assertEquals(1, api.sent.size)
    }

    @Test
    fun aRotatedTokenIsRegisteredAgain() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(isLoggedIn = true)

        registrar.onNewToken("token-2")
        registrar.registerIfNeeded(isLoggedIn = true)

        assertEquals(listOf("token-1", "token-2"), api.sent.map { it.token })
    }

    @Test
    fun aFailedRequestKeepsTheTokenPendingForTheNextRetry() = runTest {
        val api = FakeDeviceTokenApi(failuresBeforeSuccess = 1)
        val store = InMemoryDeviceTokenStore()
        val registrar = DeviceTokenRegistrar(api, store)
        registrar.onNewToken("token-1")

        assertIs<DeviceTokenRegistration.RetryLater>(registrar.registerIfNeeded(isLoggedIn = true))
        assertNull(store.registeredToken)

        assertIs<DeviceTokenRegistration.Registered>(registrar.registerIfNeeded(isLoggedIn = true))
        assertEquals("token-1", store.registeredToken)
    }

    @Test
    fun afterLogoutTheNextUserRegistersTheSameDeviceAgain() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(isLoggedIn = true)

        registrar.onLogout()
        registrar.registerIfNeeded(isLoggedIn = true)

        assertEquals(2, api.sent.size)
    }

    @Test
    fun aLogoutDuringAnInFlightRequestDoesNotMarkTheTokenAsRegistered() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeDeviceTokenApi(gate = gate)
        val store = InMemoryDeviceTokenStore()
        // Cada llamada a Notifications.registrar(...) crea su propia instancia; comparten solo el store.
        val firstUser = DeviceTokenRegistrar(api, store)
        firstUser.onNewToken("token-1")

        val inFlight = async { firstUser.registerIfNeeded(isLoggedIn = true) }
        testScheduler.runCurrent()
        DeviceTokenRegistrar(api, store).onLogout()
        gate.complete(Unit)
        inFlight.await()

        assertNull(store.registeredToken)
        assertIs<DeviceTokenRegistration.Registered>(DeviceTokenRegistrar(api, store).registerIfNeeded(isLoggedIn = true))
        assertEquals(2, api.sent.size)
    }

    @Test
    fun withoutAnyTokenThereIsNothingToSend() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())

        assertIs<DeviceTokenRegistration.NoToken>(registrar.registerIfNeeded(isLoggedIn = true))
        assertTrue(api.sent.isEmpty())
    }

    private class FakeDeviceTokenApi(
        private var failuresBeforeSuccess: Int = 0,
        private var gate: CompletableDeferred<Unit>? = null,
    ) : DeviceTokenApi {
        val sent = mutableListOf<DeviceTokenRequest>()

        override suspend fun register(request: DeviceTokenRequest) {
            gate?.let { gate = null; it.await() }
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                error("Sin señal")
            }
            sent += request
        }
    }
}
