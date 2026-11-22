package com.agrotech.oviapp.auth

import com.agrotech.oviapp.auth.data.AuthServerException
import com.agrotech.oviapp.auth.data.AuthService
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.data.LoginResponse
import com.agrotech.oviapp.auth.data.UserDto
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeSessionStorage : SessionStorage {
    private var currentSession: AuthSession? = null

    override fun saveSession(session: AuthSession) {
        currentSession = session
    }

    override fun getSession(): AuthSession? = currentSession

    override fun clearSession() {
        currentSession = null
    }

    override fun hasSession(): Boolean = currentSession != null
}

class FakeAuthService(
    var mockSession: AuthSession? = null,
    var shouldFail: Boolean = false,
    var failureException: Exception = Exception("Network error")
) : AuthService {
    var wasClosed = false

    override suspend fun login(username: String, password: String): AuthSession {
        if (shouldFail) throw failureException
        return mockSession ?: AuthSession(
            accessToken = "mock-jwt-token",
            refreshToken = "mock-refresh-token",
            username = username,
            role = "OWNER",
            ranchId = "ranch-uuid-1",
            permissions = mapOf("capture" to true)
        )
    }

    override fun close() {
        wasClosed = true
    }
}

class AuthRepositoryTest {

    @Test
    fun testLoginSuccessSavesSessionInStorage() = runTest {
        val storage = FakeSessionStorage()
        val service = FakeAuthService()
        val repository = AuthRepository(service, storage)

        assertFalse(repository.hasValidSession())
        assertNull(repository.getCachedSession())

        val result = repository.login("don_chente", "secreto123")
        assertTrue(result.isSuccess)

        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("don_chente", session.username)
        assertEquals("mock-jwt-token", session.accessToken)
        assertTrue(repository.hasValidSession())
        assertEquals(session, repository.getCachedSession())
    }

    @Test
    fun testOfflineSessionAvailableWithoutNetwork() {
        val storage = FakeSessionStorage()
        val offlineSession = AuthSession(
            accessToken = "saved-offline-token",
            refreshToken = "saved-offline-refresh",
            username = "vaquero1",
            role = "WORKER_BRANCH",
            ranchId = "rancho-san-pedro",
            permissions = mapOf("capture" to true, "read_inventory" to true)
        )
        storage.saveSession(offlineSession)

        // Offline: AuthService will throw exception if called, but cached session works
        val offlineFailingService = FakeAuthService(shouldFail = true)
        val repository = AuthRepository(offlineFailingService, storage)

        assertTrue(repository.hasValidSession())
        val cached = repository.getCachedSession()
        assertNotNull(cached)
        assertEquals("vaquero1", cached.username)
        assertEquals("WORKER_BRANCH", cached.role)
        assertTrue(cached.isWorkerBranch)
        assertTrue(cached.hasPermission("capture"))
    }

    @Test
    fun testLogoutClearsSessionFromStorage() = runTest {
        val storage = FakeSessionStorage()
        val service = FakeAuthService()
        val repository = AuthRepository(service, storage)

        repository.login("admin", "pass")
        assertTrue(repository.hasValidSession())

        repository.logout()
        assertFalse(repository.hasValidSession())
        assertNull(repository.getCachedSession())
    }

    @Test
    fun testHttpsEnforcementRejectsHttpUrls() {
        assertFailsWith<SecurityException> {
            KtorAuthService.enforceHttps("http://insecure-api.agrotech.com/api/v1")
        }
        // Valid HTTPS URL does not throw
        KtorAuthService.enforceHttps("https://du6aq4me7fp0q.cloudfront.net/api/v1")
    }

    @Test
    fun testLoginResponseParsing() {
        val resp = LoginResponse(
            accessToken = "jwt-abc",
            refreshToken = "refresh-xyz",
            role = "ROLE_OWNER",
            ranchId = "ranch-123",
            user = UserDto(username = "propietario")
        )
        assertEquals("jwt-abc", resp.resolveAccessToken())
        assertEquals("refresh-xyz", resp.resolveRefreshToken())
        assertEquals("OWNER", resp.resolveRole())
        assertEquals("ranch-123", resp.resolveRanchId())
        assertEquals("propietario", resp.resolveUsername("fallback"))
    }

    @Test
    fun testCancellationExceptionIsRethrown() = runTest {
        val storage = FakeSessionStorage()
        val service = FakeAuthService(
            shouldFail = true,
            failureException = CancellationException("Coroutines job cancelled")
        )
        val repository = AuthRepository(service, storage)

        assertFailsWith<CancellationException> {
            repository.login("user", "pass")
        }
    }

    @Test
    fun testRepositoryCloseClosesAuthService() {
        val storage = FakeSessionStorage()
        val service = FakeAuthService()
        val repository = AuthRepository(service, storage)

        assertFalse(service.wasClosed)
        repository.close()
        assertTrue(service.wasClosed)
    }

    @Test
    fun testBlankOrWhitespaceTokenValidation() {
        val emptyTokenResponse = LoginResponse(accessToken = "")
        val whitespaceTokenResponse = LoginResponse(accessToken = "   ")
        val validTokenResponse = LoginResponse(accessToken = "valid-token")

        val token1 = emptyTokenResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
        val token2 = whitespaceTokenResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
        val token3 = validTokenResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }

        assertNull(token1)
        assertNull(token2)
        assertEquals("valid-token", token3)
    }
}
