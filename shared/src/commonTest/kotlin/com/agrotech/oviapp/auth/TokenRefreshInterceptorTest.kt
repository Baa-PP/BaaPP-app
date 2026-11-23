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
import io.ktor.client.request.header
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
            ranchId = "ranch-1",
            sessionId = "session-1"
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
        assertEquals("session-1", updatedSession.sessionId)
    }

    @Test
    fun testInvalidOrRevokedRefreshTokenClearsSession() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "revoked-refresh-token",
            username = "prod1",
            role = "OWNER",
            sessionId = "session-1"
        )
        storage.saveSession(initialSession)

        val resourceEngine = MockEngine { _ ->
            respond(
                content = """{"error":"Token expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

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
            role = "OWNER",
            sessionId = "session-1"
        )
        storage.saveSession(initialSession)

        val resourceEngine = MockEngine { _ ->
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

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
        assertTrue(storage.hasSession(), "Session must be preserved when refresh fails due to network error")
        assertEquals("expired-token", storage.getSession()?.accessToken)
        assertEquals("valid-refresh-token", storage.getSession()?.refreshToken)
    }

    @Test
    fun testDifferentHostDoesNotReceiveAuthHeadersOrRefresh() = runTest {
        val storage = FakeSessionStorage()
        val session = AuthSession(
            accessToken = "secret-jwt-token",
            refreshToken = "secret-refresh-token",
            username = "prod1",
            role = "OWNER",
            ranchId = "ranch-123",
            sessionId = "session-1"
        )
        storage.saveSession(session)

        var authHeaderSent: String? = null
        var ranchHeaderSent: String? = null
        var refreshCalls = 0

        val externalEngine = MockEngine { request ->
            authHeaderSent = request.headers[HttpHeaders.Authorization]
            ranchHeaderSent = request.headers["X-Active-Ranch-Id"]
            respond(
                content = """{"error":"Access Denied"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val refreshEngine = MockEngine { _ ->
            refreshCalls++
            respond(
                content = """{"accessToken":"new-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val apiClient = HttpClient(externalEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://du6aq4me7fp0q.cloudfront.net/api/v1",
            refreshClient = refreshClient
        )
        interceptor.attachTo(apiClient)

        // Make request to external host (e.g. S3 bucket)
        val response = apiClient.get("https://s3.amazonaws.com/agrotech-media/photos/sheep1.jpg")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(authHeaderSent, "External host must NOT receive Authorization header")
        assertNull(ranchHeaderSent, "External host must NOT receive X-Active-Ranch-Id header")
        assertEquals(0, refreshCalls, "External host 401 must NOT trigger auth refresh")
    }

    @Test
    fun testSuccessfulRefreshWithMissingTokenFieldsDoesNotClearStoredSession() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "current-token",
            refreshToken = "current-refresh",
            username = "prod1",
            role = "OWNER",
            sessionId = "session-1"
        )
        storage.saveSession(initialSession)

        val refreshCallCount = AtomicInteger(0)
        val resourceCallCount = AtomicInteger(0)

        val resourceEngine = MockEngine { _ ->
            resourceCallCount.incrementAndGet()
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Refresh returns 200 OK but with empty/missing token fields (e.g. malformed JSON response)
        val refreshEngine = MockEngine { _ ->
            refreshCallCount.incrementAndGet()
            respond(
                content = """{"data": null, "accessToken": ""}""",
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

        val response = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, refreshCallCount.get(), "Expected exactly one refresh call")
        assertEquals(1, resourceCallCount.get(), "Expected exactly one resource call (no retry on empty token)")
        // Missing token fields on 200 OK must NOT clear the stored session!
        assertTrue(storage.hasSession(), "Session must NOT be cleared when 200 response has missing token fields")
        assertEquals("current-token", storage.getSession()?.accessToken)
    }

    @Test
    fun testReplacementAccountPreventsReplayingOriginalRequest() = runTest {
        val storage = FakeSessionStorage()
        val userASession = AuthSession(
            accessToken = "user-a-expired-token",
            refreshToken = "user-a-refresh",
            username = "user_a",
            role = "OWNER",
            ranchId = "ranch-a",
            sessionId = "session-a"
        )
        storage.saveSession(userASession)

        var requestCount = 0

        val resourceEngine = MockEngine { request ->
            requestCount++
            val auth = request.headers[HttpHeaders.Authorization]
            if (auth == "Bearer user-a-expired-token") {
                // Simulate: while this request is being processed, User B logs in!
                storage.saveSession(
                    AuthSession(
                        accessToken = "user-b-active-token",
                        refreshToken = "user-b-refresh",
                        username = "user_b",
                        role = "OWNER",
                        ranchId = "ranch-b",
                        sessionId = "session-b"
                    )
                )
                respond(
                    content = """{"error":"Unauthorized"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = """{"status":"ok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        val refreshEngine = MockEngine { _ ->
            respond(
                content = """{"accessToken":"user-a-new-token"}""",
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

        val response = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")

        // Should return 401 and NOT replay with User B's credentials
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, requestCount, "Request must NOT be replayed with replacement account's credentials")
        // User B's session must remain intact in storage!
        assertEquals("user_b", storage.getSession()?.username)
        assertEquals("session-b", storage.getSession()?.sessionId)
    }

    @Test
    fun testConcurrent401CallsTriggerOnlyOneRefresh() = runTest {
        val storage = FakeSessionStorage()
        val initialSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "valid-refresh",
            username = "worker",
            role = "WORKER_BRANCH",
            sessionId = "session-1"
        )
        storage.saveSession(initialSession)

        val refreshCallCount = AtomicInteger(0)
        val expiredArrivals = AtomicInteger(0)
        val allThreeArrived = kotlinx.coroutines.CompletableDeferred<Unit>()

        val resourceEngine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            if (auth == "Bearer expired-token") {
                if (expiredArrivals.incrementAndGet() >= 3) {
                    allThreeArrived.complete(Unit)
                } else {
                    allThreeArrived.await()
                }
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

    @Test
    fun testClientCloseClosesRefreshClient() = runTest {
        val storage = FakeSessionStorage()

        val refreshEngine = MockEngine { _ ->
            respond(
                content = """{"accessToken":"token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val authenticatedClient = TokenRefreshInterceptor.createAuthenticatedClient(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        )

        // Closing the authenticated client should close the refresh client
        authenticatedClient.close()

        val authJob = authenticatedClient.coroutineContext[kotlinx.coroutines.Job]
        val refreshJob = refreshClient.coroutineContext[kotlinx.coroutines.Job]

        authJob?.join()
        refreshJob?.join()

        // Both clients' jobs must be inactive after closing and awaiting authenticatedClient
        assertFalse(authJob?.isActive ?: true, "Authenticated client job must be inactive")
        assertFalse(refreshJob?.isActive ?: true, "Refresh client job must be inactive")
    }

    @Test
    fun testCrossOriginRedirectStripsRanchHeaderWhileSameOriginPreservesIt() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "tok-1",
                refreshToken = "ref-1",
                username = "owner",
                role = "OWNER",
                ranchId = "ranch-99",
                sessionId = "sess-1"
            )
        )

        val observedRanchHeaders = mutableListOf<Pair<String, String?>>()

        val apiEngine = MockEngine { request ->
            val host = request.url.host
            observedRanchHeaders += host to request.headers[TokenRefreshInterceptor.HEADER_ACTIVE_RANCH_ID]
            when (request.url.encodedPath) {
                "/api/v1/redirect-external" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.external.com/media.jpg")
                )
                "/api/v1/redirect-internal" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://api.baapp.mx/api/v1/animals")
                )
                else -> respond(
                    content = """{"ok":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        val client = HttpClient(apiEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val interceptor = TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        )
        interceptor.attachTo(client)

        val extResp = client.get("https://api.baapp.mx/api/v1/redirect-external")
        assertEquals(HttpStatusCode.OK, extResp.status)

        val intResp = client.get("https://api.baapp.mx/api/v1/redirect-internal")
        assertEquals(HttpStatusCode.OK, intResp.status)

        // First hop to api.baapp.mx has ranch header; redirect to cdn.external.com must NOT have it
        assertEquals("api.baapp.mx" to "ranch-99", observedRanchHeaders[0])
        assertEquals("cdn.external.com" to null, observedRanchHeaders[1])
        // Same-origin redirect preserves ranch header
        assertEquals("api.baapp.mx" to "ranch-99", observedRanchHeaders[2])
        assertEquals("api.baapp.mx" to "ranch-99", observedRanchHeaders[3])
    }

    @Test
    fun testForeignAuthorizationHeaderDoesNotTriggerRefreshOrReplay() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "session-token",
                refreshToken = "session-refresh",
                username = "owner",
                role = "OWNER",
                ranchId = "ranch-1",
                sessionId = "sess-1"
            )
        )

        var refreshCalled = false
        var apiCalls = 0

        val apiEngine = MockEngine { _ ->
            apiCalls++
            respond(
                content = """{"error":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val refreshEngine = MockEngine { _ ->
            refreshCalled = true
            respond(
                content = """{"accessToken":"new-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(apiEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }
        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = refreshClient
        ).attachTo(client)

        val response = client.get("https://api.baapp.mx/api/v1/animals") {
            header(HttpHeaders.Authorization, "Bearer foreign-account-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(refreshCalled, "Refresh must not be called for foreign credentials")
        assertEquals(1, apiCalls, "Request with foreign credentials must not be replayed")
        assertEquals("session-token", storage.getSession()?.accessToken)
    }

    @Test
    fun testAtomicGenerationMutationRejectsStaleSessionUpdates() = runTest {
        val storage = FakeSessionStorage()
        val sess1 = AuthSession(
            accessToken = "expired-tok-1",
            refreshToken = "ref-1",
            username = "user1",
            role = "OWNER",
            sessionId = "sess-1"
        )
        storage.saveSession(sess1)

        // Mismatched sessionId / refreshToken direct unit checks
        assertFalse(storage.updateSessionIfMatches("sess-0", "ref-1", "expired-tok-1") { it.copy(accessToken = "stale-tok") })
        assertFalse(storage.clearSessionIfMatches("sess-0", "ref-1", "expired-tok-1"))
        assertFalse(storage.updateSessionIfMatches("sess-1", "wrong-ref", "expired-tok-1") { it.copy(accessToken = "stale-tok") })
        assertFalse(storage.clearSessionIfMatches("sess-1", "wrong-ref", "expired-tok-1"))
        assertFalse(storage.updateSessionIfMatches("sess-1", "ref-1", "wrong-access") { it.copy(accessToken = "stale-tok") })

        // Now test through the interceptor: pause refresh while storage switches to another account
        val refreshStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowRefreshComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

        var apiCalls = 0
        val apiEngine = MockEngine { request ->
            apiCalls++
            respondError(HttpStatusCode.Unauthorized)
        }

        val refreshEngine = MockEngine { request ->
            refreshStarted.complete(Unit)
            allowRefreshComplete.await()
            respond(
                content = """{"accessToken":"stale-new-token","refreshToken":"stale-new-refresh"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = HttpClient(apiEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val refreshClient = HttpClient(refreshEngine) {
            install(ContentNegotiation) { json(json) }
        }

        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = refreshClient
        ).attachTo(apiClient)

        val requestJob = async {
            apiClient.get("https://api.baapp.mx/api/v1/animals")
        }

        refreshStarted.await()

        // Account is replaced while refresh is in-flight
        val replacementSession = AuthSession(
            accessToken = "replacement-token",
            refreshToken = "replacement-refresh",
            username = "user2",
            role = "OWNER",
            sessionId = "sess-2"
        )
        storage.saveSession(replacementSession)

        allowRefreshComplete.complete(Unit)
        val response = requestJob.await()

        // Original request must return 401 Unauthorized without being replayed against replacement account
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, apiCalls, "Original request must not be replayed across accounts")

        // Replacement session remains completely intact
        assertEquals("replacement-token", storage.getSession()?.accessToken)
        assertEquals("sess-2", storage.getSession()?.sessionId)
        assertEquals("user2", storage.getSession()?.username)
    }

    @Test
    fun testRefreshClientWithExpectSuccessTrueStillClearsSessionOn401() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "expired-tok",
                refreshToken = "revoked-ref",
                username = "owner",
                role = "OWNER",
                sessionId = "sess-1"
            )
        )

        val apiClient = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }) {
            install(ContentNegotiation) { json(json) }
        }
        val refreshClient = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }

        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient = refreshClient
        ).attachTo(apiClient)

        val resp = apiClient.get("https://du6aq4me7fp0q.cloudfront.net/api/v1/animals")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertNull(storage.getSession(), "Revoked refresh must clear session even when refreshClient has expectSuccess=true")
    }

    @Test
    fun testSameOriginRedirectRecoversWithRefreshedTokenInSameGeneration() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "old-token",
                refreshToken = "ref-1",
                username = "owner",
                role = "OWNER",
                ranchId = "ranch-1",
                sessionId = "sess-1"
            )
        )

        var refreshCalled = false
        val observedTokensOnTarget = mutableListOf<String?>()

        val apiEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/redirect-me" -> {
                    // Simulate a concurrent refresh completing before the redirect hop runs
                    storage.saveSession(
                        storage.getSession()!!.copy(accessToken = "refreshed-token")
                    )
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://api.baapp.mx/api/v1/target")
                    )
                }
                "/api/v1/target" -> {
                    val auth = request.headers[HttpHeaders.Authorization]
                    observedTokensOnTarget += auth
                    if (auth == "Bearer refreshed-token") {
                        respond(
                            content = """{"ok":true}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond(
                            content = """{"error":"expired"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val refreshClient = HttpClient(MockEngine { _ ->
            refreshCalled = true
            respond("""{"accessToken":"unused"}""", HttpStatusCode.OK)
        }) {
            install(ContentNegotiation) { json(json) }
        }

        val client = HttpClient(apiEngine) {
            install(ContentNegotiation) { json(json) }
        }
        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = refreshClient
        ).attachTo(client)

        val response = client.get("https://api.baapp.mx/api/v1/redirect-me")

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(refreshCalled, "Should recover using the already-refreshed token in storage")
        assertEquals(listOf<String?>("Bearer old-token", "Bearer refreshed-token"), observedTokensOnTarget)
    }

    @Test
    fun testMissingRefreshTokenReturns401WithoutClearingSession() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "expired-token",
                refreshToken = null,
                username = "owner",
                role = "OWNER",
                sessionId = "sess-1"
            )
        )

        val apiClient = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }) {
            install(ContentNegotiation) { json(json) }
        }
        val refreshClient = HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) }) {
            install(ContentNegotiation) { json(json) }
        }

        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = refreshClient
        ).attachTo(apiClient)

        val response = apiClient.get("https://api.baapp.mx/api/v1/animals")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(storage.hasSession(), "Missing refresh token must preserve session for offline access")
    }

    @Test
    fun testRedirectStopsWhenSessionGenerationChangesAcrossHop() = runTest {
        val storage = FakeSessionStorage()
        storage.saveSession(
            AuthSession(
                accessToken = "token-a",
                refreshToken = "ref-a",
                username = "userA",
                role = "OWNER",
                sessionId = "sess-a"
            )
        )

        var targetReached = false
        val apiEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/start" -> {
                    // Account switches while redirect is in progress
                    storage.saveSession(
                        AuthSession(
                            accessToken = "token-b",
                            refreshToken = "ref-b",
                            username = "userB",
                            role = "OWNER",
                            sessionId = "sess-b"
                        )
                    )
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://api.baapp.mx/api/v1/next")
                    )
                }
                "/api/v1/next" -> {
                    targetReached = true
                    respond("""{"ok":true}""", HttpStatusCode.OK)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(apiEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val refreshClient = HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })
        TokenRefreshInterceptor(
            sessionStorage = storage,
            baseUrl = "https://api.baapp.mx/api/v1",
            refreshClient = refreshClient
        ).attachTo(client)

        var threw = false
        try {
            client.get("https://api.baapp.mx/api/v1/start")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "Redirect chain must stop when session generation changes")
        assertFalse(targetReached, "Redirect target must not be reached after generation mismatch")
    }
}
