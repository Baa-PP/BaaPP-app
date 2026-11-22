package com.agrotech.oviapp.auth

import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.data.TokenRefreshInterceptor
import com.agrotech.oviapp.auth.domain.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenRefreshInterceptorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testValidRefreshRetriesRequestTransparently() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "valid-refresh-token",
            username = "prod1",
            role = "OWNER",
            ranchId = "ranch-1"
        )
        storage.saveSession(initialSession)

        var resourceCalls = 0
        var refreshCalls = 0

        // Mock engine for protected resource
        val resourceEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("/animals")) {
                resourceCalls++
                val authHeader = request.headers[HttpHeaders.Authorization]
                if (authHeader == "Bearer expired-token") {
                    respond(
                        content = """{"error":"Token expired"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (authHeader == "Bearer new-valid-token") {
                    respond(
                        content = """{"animals":[{"id":"1","folio":"PEL-01"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respondError(HttpStatusCode.Unauthorized)
                }
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }

        // Mock engine for /auth/refresh
        val refreshEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("/auth/refresh")) {
                refreshCalls++
                respond(
                    content = """{"accessToken":"new-valid-token","refreshToken":"rotated-refresh-token"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val apiClient = HttpClient(resourceEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        )
        interceptor.attachTo(apiClient)

        // Make the request
        val response = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")

        // Assertions: user gets 200 OK seamlessly
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("PEL-01"))
        assertEquals(2, resourceCalls, "Resource should be called twice (initial 401, then retried 200)")
        assertEquals(1, refreshCalls, "Refresh endpoint should be called exactly once")

        // Assert session updated in storage
        val updatedSession = storage.getSession()
        assertNotNull(updatedSession)
        assertEquals("new-valid-token", updatedSession.accessToken)
        assertEquals("rotated-refresh-token", updatedSession.refreshToken)
    }

    @Test
    fun testInvalidOrRevokedRefreshTokenClearsSession() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "revoked-refresh-token",
            username = "prod1",
            role = "OWNER"
        )
        storage.saveSession(initialSession)

        // Mock engine for protected resource
        val resourceEngine = MockEngine { _ ->
            respond(
                content = """{"error":"Token expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Mock engine for /auth/refresh returning 401 (revoked/expired refresh token)
        val refreshEngine = MockEngine { _ ->
            respond(
                content = """{"error":"Refresh token revoked"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val apiClient = HttpClient(resourceEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        )
        interceptor.attachTo(apiClient)

        val response = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // Refresh token was invalid/revoked -> Session MUST be cleared
        assertFalse(storage.hasSession(), "Session should be cleared on invalid/revoked refresh token")
        assertNull(storage.getSession())
    }

    @Test
    fun testNetworkErrorDuringRefreshPreservesSessionForOfflineUse() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "valid-refresh-token",
            username = "prod1",
            role = "OWNER"
        )
        storage.saveSession(initialSession)

        val resourceEngine = MockEngine { _ ->
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Mock engine for /auth/refresh simulating network failure (IOException / no connection)
        val refreshEngine = MockEngine { _ ->
            throw IOException("No route to host / connection timed out")
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val apiClient = HttpClient(resourceEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        )
        interceptor.attachTo(apiClient)

        val response = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // Network error -> Session MUST NOT be cleared
        assertTrue(storage.hasSession(), "Session must be preserved when refresh fails due to network error")
        assertEquals("expired-token", storage.getSession()?.accessToken)
        assertEquals("valid-refresh-token", storage.getSession()?.refreshToken)
    }

    @Test
    fun testConcurrent401CallsTriggerOnlyOneRefresh() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "valid-refresh",
            username = "worker",
            role = "WORKER_BRANCH"
        )
        storage.saveSession(initialSession)

        val refreshCallCount = AtomicInteger(0)

        val resourceEngine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            if (auth == "Bearer expired-token") {
                respondError(HttpStatusCode.Unauthorized)
            } else if (auth == "Bearer new-token") {
                respond(
                    content = """{"status":"ok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.Unauthorized)
            }
        }

        val refreshEngine = MockEngine { _ ->
            refreshCallCount.incrementAndGet()
            respond(
                content = """{"accessToken":"new-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val apiClient = HttpClient(resourceEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        )
        interceptor.attachTo(apiClient)

        // Launch 3 concurrent requests that both encounter 401
        val req1 = async { apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals") }
        val req2 = async { apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/weights") }
        val req3 = async { apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/births") }

        val responses = awaitAll(req1, req2, req3)

        // All 3 should succeed
        responses.forEach { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }

        // Mutex guarantees only 1 refresh call was made
        assertEquals(1, refreshCallCount.get(), "Only one refresh call should be made for concurrent 401s")
        assertEquals("new-token", storage.getSession()?.accessToken)
    }
}
