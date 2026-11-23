package com.agrotech.oviapp.auth.data

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.utils.EmptyContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Ktor interceptor in :shared that consumes POST /api/v1/auth/refresh when network recovers.
 *
 * Requirements (APP-03 + security review):
 * - Scheme, host, and port origin matching: Authorization & X-Active-Ranch-Id are only added
 *   to matching origins; foreign origins (e.g. S3 presigned URLs) skip headers, refresh, and retry.
 * - Session generation awareness: distinguishes account replacement from token refresh, preventing
 *   cross-account replay or stale refresh overwrites.
 * - A 401 with a valid refresh token retries the request seamlessly within the same session generation.
 * - An invalid or revoked refresh token closes the session only if the stored session is unchanged.
 * - A 200 response with missing/empty token fields fails the refresh without clearing the stored session.
 * - Preserves session on connection/network errors.
 */
class TokenRefreshInterceptor(
    private val sessionStorage: SessionStorage,
    private val baseUrl: String = KtorAuthService.DEFAULT_BASE_URL,
    private val refreshClient: HttpClient = createDefaultRefreshClient()
) : AutoCloseable {

    private val refreshMutex = Mutex()
    private val baseOrigin = Url(baseUrl)

    init {
        KtorAuthService.enforceHttps(baseUrl)
    }

    private fun isMatchingOrigin(requestUrl: Url): Boolean {
        return requestUrl.protocol == baseOrigin.protocol &&
               requestUrl.host.equals(baseOrigin.host, ignoreCase = true) &&
               requestUrl.port == baseOrigin.port
    }

    /**
     * Attaches this interceptor to an existing HttpClient instance.
     */
    fun attachTo(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            val requestUrl = request.url.build()
            val matchesOrigin = isMatchingOrigin(requestUrl)
            val isAuthEndpoint = request.url.encodedPath.contains("/auth/")

            // 1. Add credentials only if request matches baseUrl's origin and is not an auth endpoint.
            // Retain the initiating account generation independently of INJECTED_CREDENTIALS_KEY across every redirect hop.
            val currentSessionForHop = sessionStorage.getSession()
            val existingInitiatingSessionId = request.attributes.getOrNull(INITIATING_SESSION_ID_KEY)
            if (existingInitiatingSessionId != null && currentSessionForHop?.sessionId != existingInitiatingSessionId) {
                request.headers.remove(HttpHeaders.Authorization)
                request.headers.remove(HEADER_ACTIVE_RANCH_ID)
                request.attributes.remove(INJECTED_CREDENTIALS_KEY)
                throw IllegalStateException("Session generation changed during redirect")
            }

            val capturedSession = if (matchesOrigin && !isAuthEndpoint) currentSessionForHop else null
            val existingProvenance = request.attributes.getOrNull(INJECTED_CREDENTIALS_KEY)
            if (existingInitiatingSessionId == null && capturedSession != null) {
                request.attributes.put(INITIATING_SESSION_ID_KEY, capturedSession.sessionId)
            }

            if (!matchesOrigin) {
                request.headers.remove(HEADER_ACTIVE_RANCH_ID)
                if (existingProvenance != null) {
                    request.headers.remove(HttpHeaders.Authorization)
                }
            } else if (!isAuthEndpoint) {
                val generationMatches = capturedSession != null &&
                    (existingProvenance == null || existingProvenance.sessionId == capturedSession.sessionId)
                if (generationMatches) {
                    if (!request.headers.contains(HttpHeaders.Authorization) && capturedSession!!.accessToken.isNotBlank()) {
                        request.header(HttpHeaders.Authorization, "Bearer ${capturedSession.accessToken}")
                        request.attributes.put(
                            INJECTED_CREDENTIALS_KEY,
                            InjectedCredentials(
                                sessionId = capturedSession.sessionId,
                                accessToken = capturedSession.accessToken
                            )
                        )
                    }
                    if (!request.headers.contains(HEADER_ACTIVE_RANCH_ID) && !capturedSession!!.ranchId.isNullOrBlank()) {
                        request.header(HEADER_ACTIVE_RANCH_ID, capturedSession.ranchId)
                    }
                } else {
                    // capturedSession is absent or existingProvenance indicates a different session generation:
                    // Remove interceptor-injected credentials
                    if (existingProvenance != null) {
                        request.headers.remove(HttpHeaders.Authorization)
                        request.headers.remove(HEADER_ACTIVE_RANCH_ID)
                        request.attributes.remove(INJECTED_CREDENTIALS_KEY)
                    }
                }
            }

            // 2. Execute request
            val originalCall = execute(request)

            // 3. Intercept 401 only for matching origin on protected endpoints
            if (originalCall.response.status == HttpStatusCode.Unauthorized && matchesOrigin && !isAuthEndpoint) {
                val tokenUsed = request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()
                val provenance = request.attributes.getOrNull(INJECTED_CREDENTIALS_KEY)
                val originSessionId = provenance?.sessionId ?: capturedSession?.sessionId
                val originAccessToken = provenance?.accessToken ?: capturedSession?.accessToken

                val refreshSucceeded = refreshMutex.withLock {
                    val currentSession = sessionStorage.getSession()

                    when {
                        // No session or missing origin session (blank originSessionId is treated as having no matching session)
                        originSessionId.isNullOrBlank() || originAccessToken == null || currentSession == null -> {
                            false
                        }
                        // Replacement account has taken over: do not replay request across accounts!
                        currentSession.sessionId != originSessionId -> {
                            false
                        }
                        // Confirm the request's original credentials belonged to the origin session
                        tokenUsed != originAccessToken -> {
                            false
                        }
                        // Token changed within the SAME session generation (concurrent refresh or redirect recovery)
                        currentSession.accessToken != tokenUsed && currentSession.accessToken.isNotBlank() -> {
                            true
                        }
                        // Stored refresh token is missing or blank: return original 401 without clearing session (preserve offline access)
                        currentSession.refreshToken.isNullOrBlank() -> {
                            false
                        }
                        // Need to call POST /api/v1/auth/refresh
                        else -> {
                            performRefresh(currentSession.refreshToken, currentSession)
                        }
                    }
                }

                if (refreshSucceeded && isReplayableBody(request.body)) {
                    val newSession = sessionStorage.getSession()
                    if (newSession != null &&
                        originSessionId != null &&
                        newSession.sessionId == originSessionId &&
                        newSession.accessToken.isNotBlank()
                    ) {
                        request.headers.remove(HttpHeaders.Authorization)
                        request.header(HttpHeaders.Authorization, "Bearer ${newSession.accessToken}")
                        request.attributes.put(
                            INJECTED_CREDENTIALS_KEY,
                            InjectedCredentials(
                                sessionId = newSession.sessionId,
                                accessToken = newSession.accessToken
                            )
                        )
                        return@intercept execute(request)
                    }
                }
            }

            originalCall
        }
    }

    private fun isReplayableBody(body: Any): Boolean = when (body) {
        is EmptyContent,
        is OutgoingContent.NoContent,
        is OutgoingContent.ByteArrayContent -> true
        is OutgoingContent -> false
        else -> true
    }

    private suspend fun performRefresh(
        refreshToken: String,
        initialSession: AuthSession
    ): Boolean {
        val refreshUrl = baseUrl.trimEnd('/') + "/auth/refresh"
        KtorAuthService.enforceHttps(refreshUrl)

        return try {
            val response = refreshClient.post(refreshUrl) {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(RefreshRequest(refreshToken = refreshToken))
            }

            when {
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.BadRequest -> {
                    // Atomically invalidate only if stored session & submitted snapshot still match
                    sessionStorage.clearSessionIfMatches(
                        expectedSessionId = initialSession.sessionId,
                        expectedRefreshToken = refreshToken,
                        expectedAccessToken = initialSession.accessToken
                    )
                    false
                }
                response.status.isSuccess() -> {
                    val refreshResponse = response.body<RefreshResponse>()
                    val newAccessToken = refreshResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
                    if (newAccessToken != null) {
                        val newRefreshToken = refreshResponse.resolveRefreshToken()?.takeIf { it.isNotBlank() }
                            ?: initialSession.refreshToken
                        sessionStorage.updateSessionIfMatches(
                            expectedSessionId = initialSession.sessionId,
                            expectedRefreshToken = refreshToken,
                            expectedAccessToken = initialSession.accessToken
                        ) { stored ->
                            stored.copy(
                                accessToken = newAccessToken,
                                refreshToken = newRefreshToken
                            )
                        }
                    } else {
                        // Missing/empty token fields on 200 OK: fail refresh WITHOUT clearing stored session
                        false
                    }
                }
                else -> {
                    // Server 5xx, etc. -> do not clear session
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network failure: preserve session for offline use
            false
        }
    }

    override fun close() {
        refreshClient.close()
    }

    private data class InjectedCredentials(
        val sessionId: String,
        val accessToken: String
    )

    companion object {
        const val HEADER_ACTIVE_RANCH_ID = "X-Active-Ranch-Id"
        private val INITIATING_SESSION_ID_KEY =
            AttributeKey<String>("TokenRefreshInitiatingSessionId")
        private val INJECTED_CREDENTIALS_KEY =
            AttributeKey<InjectedCredentials>("TokenRefreshInjectedCredentials")

        fun createDefaultRefreshClient(): HttpClient {
            return HttpClient {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                    })
                }
            }
        }

        /**
         * Helper to create an authenticated HttpClient with TokenRefreshInterceptor pre-installed
         * and lifecycle connected so closing the returned client automatically cleans up refreshClient.
         */
        fun createAuthenticatedClient(
            sessionStorage: SessionStorage,
            baseUrl: String = KtorAuthService.DEFAULT_BASE_URL,
            refreshClient: HttpClient? = null
        ): HttpClient {
            KtorAuthService.enforceHttps(baseUrl)

            val effectiveRefreshClient = refreshClient ?: createDefaultRefreshClient()
            val ownsRefreshClient = (refreshClient == null)

            var clientCreated: HttpClient? = null
            var interceptorCreated: TokenRefreshInterceptor? = null

            try {
                val client = HttpClient {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            coerceInputValues = true
                        })
                    }
                }
                clientCreated = client

                val interceptor = TokenRefreshInterceptor(sessionStorage, baseUrl, effectiveRefreshClient)
                interceptorCreated = interceptor
                interceptor.attachTo(client)

                client.coroutineContext[Job]?.invokeOnCompletion {
                    interceptor.close()
                }
                return client
            } catch (t: Throwable) {
                clientCreated?.close()
                interceptorCreated?.close()
                if (ownsRefreshClient) {
                    effectiveRefreshClient.close()
                }
                throw t
            }
        }
    }
}
