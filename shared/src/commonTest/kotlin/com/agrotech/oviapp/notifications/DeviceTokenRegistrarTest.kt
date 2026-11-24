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
        val result = registrar.registerIfNeeded(accountId = "user-a")

        assertIs<DeviceTokenRegistration.Registered>(result)
        assertEquals(listOf(DeviceTokenRequest(token = "token-1", platform = "ANDROID")), api.sent)
        assertEquals("token-1", store.registeredToken)
    }

    @Test
    fun waitsForLoginBeforeSendingTheToken() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())

        registrar.onNewToken("token-1")

        assertIs<DeviceTokenRegistration.NotLoggedIn>(registrar.registerIfNeeded(accountId = null))
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun doesNotResendATokenThatIsAlreadyRegistered() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(accountId = "user-a")

        val second = registrar.registerIfNeeded(accountId = "user-a")

        assertIs<DeviceTokenRegistration.AlreadyRegistered>(second)
        assertEquals(1, api.sent.size)
    }

    @Test
    fun aRotatedTokenIsRegisteredAgain() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(accountId = "user-a")

        registrar.onNewToken("token-2")
        registrar.registerIfNeeded(accountId = "user-a")

        assertEquals(listOf("token-1", "token-2"), api.sent.map { it.token })
    }

    @Test
    fun aFailedRequestKeepsTheTokenPendingForTheNextRetry() = runTest {
        val api = FakeDeviceTokenApi(failuresBeforeSuccess = 1)
        val store = InMemoryDeviceTokenStore()
        val registrar = DeviceTokenRegistrar(api, store)
        registrar.onNewToken("token-1")

        assertIs<DeviceTokenRegistration.RetryLater>(registrar.registerIfNeeded(accountId = "user-a"))
        assertNull(store.registeredToken)

        assertIs<DeviceTokenRegistration.Registered>(registrar.registerIfNeeded(accountId = "user-a"))
        assertEquals("token-1", store.registeredToken)
    }

    @Test
    fun afterLogoutTheNextUserRegistersTheSameDeviceAgain() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())
        registrar.onNewToken("token-1")
        registrar.registerIfNeeded(accountId = "user-a")

        registrar.onLogout()
        registrar.registerIfNeeded(accountId = "user-a")

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

        val inFlight = async { firstUser.registerIfNeeded(accountId = "user-a") }
        testScheduler.runCurrent()
        DeviceTokenRegistrar(api, store).onLogout()
        gate.complete(Unit)
        inFlight.await()

        assertNull(store.registeredToken)
        assertIs<DeviceTokenRegistration.Registered>(DeviceTokenRegistrar(api, store).registerIfNeeded(accountId = "user-a"))
        assertEquals(2, api.sent.size)
    }

    @Test
    fun aStaleMarkerFromAnotherAccountNeverSkipsTheRegistration() = runTest {
        // El logout del usuario anterior nunca corrió (se canceló o Android mató el proceso).
        val api = FakeDeviceTokenApi()
        val store = InMemoryDeviceTokenStore()
        DeviceTokenRegistrar(api, store).onNewToken("token-1")
        DeviceTokenRegistrar(api, store).registerIfNeeded(accountId = "user-a")

        val nextUser = DeviceTokenRegistrar(api, store).registerIfNeeded(accountId = "user-b")

        assertIs<DeviceTokenRegistration.Registered>(nextUser)
        assertEquals(2, api.sent.size)
        assertEquals("user-b", store.registeredAccount)
    }

    @Test
    fun withoutAnyTokenThereIsNothingToSend() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = DeviceTokenRegistrar(api, InMemoryDeviceTokenStore())

        assertIs<DeviceTokenRegistration.NoToken>(registrar.registerIfNeeded(accountId = "user-a"))
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
