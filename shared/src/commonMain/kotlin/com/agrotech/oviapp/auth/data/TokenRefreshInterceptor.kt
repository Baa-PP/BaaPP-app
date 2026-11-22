package com.agrotech.oviapp.auth.data

import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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

            // 1. Add credentials only if request matches baseUrl's origin and is not an auth endpoint
            val capturedSession = if (matchesOrigin && !isAuthEndpoint) sessionStorage.getSession() else null

            if (matchesOrigin && !isAuthEndpoint && capturedSession != null) {
                if (!request.headers.contains(HttpHeaders.Authorization) && capturedSession.accessToken.isNotBlank()) {
                    request.header(HttpHeaders.Authorization, "Bearer ${capturedSession.accessToken}")
                }
                if (!request.headers.contains(HEADER_ACTIVE_RANCH_ID) && !capturedSession.ranchId.isNullOrBlank()) {
                    request.header(HEADER_ACTIVE_RANCH_ID, capturedSession.ranchId)
                }
            }

            // 2. Execute request
            val originalCall = execute(request)

            // 3. Intercept 401 only for matching origin on protected endpoints
            if (originalCall.response.status == HttpStatusCode.Unauthorized && matchesOrigin && !isAuthEndpoint) {
                val tokenUsed = request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()

                val refreshSucceeded = refreshMutex.withLock {
                    val currentSession = sessionStorage.getSession()

                    when {
                        // No session or missing captured session
                        capturedSession == null || currentSession == null -> {
                            sessionStorage.clearSession()
                            false
                        }
                        // Replacement account has taken over: do not replay request across accounts!
                        currentSession.sessionId != capturedSession.sessionId -> {
                            false
                        }
                        // Token changed within the SAME session generation (concurrent refresh succeeded)
                        currentSession.accessToken != tokenUsed && currentSession.accessToken.isNotBlank() -> {
                            true
                        }
                        // Stored refresh token is missing or blank
                        currentSession.refreshToken.isNullOrBlank() -> {
                            sessionStorage.clearSession()
                            false
                        }
                        // Need to call POST /api/v1/auth/refresh
                        else -> {
                            performRefresh(currentSession.refreshToken, currentSession)
                        }
                    }
                }

                if (refreshSucceeded) {
                    val newSession = sessionStorage.getSession()
                    if (newSession != null &&
                        capturedSession != null &&
                        newSession.sessionId == capturedSession.sessionId &&
                        newSession.accessToken.isNotBlank()
                    ) {
                        request.headers.remove(HttpHeaders.Authorization)
                        request.header(HttpHeaders.Authorization, "Bearer ${newSession.accessToken}")
                        return@intercept execute(request)
                    }
                }
            }

            originalCall
        }
    }

    private suspend fun performRefresh(
        refreshToken: String,
        initialSession: AuthSession
    ): Boolean {
        val refreshUrl = baseUrl.trimEnd('/') + "/auth/refresh"
        KtorAuthService.enforceHttps(refreshUrl)

        return try {
            val response = refreshClient.post(refreshUrl) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(RefreshRequest(refreshToken = refreshToken))
            }

            when {
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.BadRequest -> {
                    // Invalidate only if stored session still belongs to this generation
                    val stored = sessionStorage.getSession()
                    if (stored != null && stored.sessionId == initialSession.sessionId) {
                        sessionStorage.clearSession()
                    }
                    false
                }
                response.status.isSuccess() -> {
                    val refreshResponse = response.body<RefreshResponse>()
                    val newAccessToken = refreshResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
                    if (newAccessToken != null) {
                        val stored = sessionStorage.getSession()
                        if (stored != null && stored.sessionId == initialSession.sessionId) {
                            val newRefreshToken = refreshResponse.resolveRefreshToken()?.takeIf { it.isNotBlank() }
                                ?: initialSession.refreshToken
                            val updatedSession = stored.copy(
                                accessToken = newAccessToken,
                                refreshToken = newRefreshToken
                            )
                            sessionStorage.saveSession(updatedSession)
                            true
                        } else {
                            // Account was replaced while refresh was running
                            false
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

    companion object {
        const val HEADER_ACTIVE_RANCH_ID = "X-Active-Ranch-Id"

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
            refreshClient: HttpClient = createDefaultRefreshClient()
        ): HttpClient {
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                    })
                }
            }
            val interceptor = TokenRefreshInterceptor(sessionStorage, baseUrl, refreshClient)
            interceptor.attachTo(client)
            client.coroutineContext[Job]?.invokeOnCompletion {
                interceptor.close()
            }
            return client
        }
    }
}
