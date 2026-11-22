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
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Ktor interceptor for :shared that consumes POST /api/v1/auth/refresh when network recovers.
 *
 * Requirements (APP-03):
 * - A 401 with a valid refresh token retries the request seamlessly.
 * - An invalid or revoked refresh token closes the session (clears secure storage).
 * - Only closes session if refresh expires or is revoked by the server (preserves session on connection/network errors).
 */
class TokenRefreshInterceptor(
    private val sessionStorage: SessionStorage,
    private val baseUrl: String = KtorAuthService.DEFAULT_BASE_URL,
    private val refreshClient: HttpClient = createDefaultRefreshClient()
) : AutoCloseable {

    private val refreshMutex = Mutex()

    init {
        KtorAuthService.enforceHttps(baseUrl)
    }

    /**
     * Attaches this interceptor to an existing HttpClient instance.
     */
    fun attachTo(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            val isAuthEndpoint = request.url.encodedPath.contains("/auth/")

            // 1. Inject current Bearer token and ranch ID if not already set and not an auth call
            val sessionBefore = sessionStorage.getSession()
            if (!isAuthEndpoint && sessionBefore != null) {
                if (!request.headers.contains(HttpHeaders.Authorization) && sessionBefore.accessToken.isNotBlank()) {
                    request.header(HttpHeaders.Authorization, "Bearer ${sessionBefore.accessToken}")
                }
                if (!request.headers.contains(HEADER_ACTIVE_RANCH_ID) && !sessionBefore.ranchId.isNullOrBlank()) {
                    request.header(HEADER_ACTIVE_RANCH_ID, sessionBefore.ranchId)
                }
            }

            // 2. Execute original request
            val originalCall = execute(request)

            // 3. Intercept 401 Unauthorized on protected endpoints
            if (originalCall.response.status == HttpStatusCode.Unauthorized && !isAuthEndpoint) {
                val tokenUsed = request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()

                val refreshSucceeded = refreshMutex.withLock {
                    val currentSession = sessionStorage.getSession()
                    val storedRefreshToken = currentSession?.refreshToken

                    if (storedRefreshToken.isNullOrBlank()) {
                        // No refresh token available, session cannot be refreshed
                        sessionStorage.clearSession()
                        false
                    } else if (currentSession.accessToken != tokenUsed && currentSession.accessToken.isNotBlank()) {
                        // Another concurrent coroutine already refreshed the token
                        true
                    } else {
                        // Call POST /api/v1/auth/refresh
                        performRefresh(storedRefreshToken, currentSession)
                    }
                }

                if (refreshSucceeded) {
                    val newSession = sessionStorage.getSession()
                    if (newSession != null && newSession.accessToken.isNotBlank()) {
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
        currentSession: AuthSession
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
                    // Refresh token invalid, expired, or revoked by server
                    sessionStorage.clearSession()
                    false
                }
                response.status.isSuccess() -> {
                    val refreshResponse = response.body<RefreshResponse>()
                    val newAccessToken = refreshResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
                    if (newAccessToken != null) {
                        val newRefreshToken = refreshResponse.resolveRefreshToken()?.takeIf { it.isNotBlank() }
                            ?: refreshToken
                        val updatedSession = currentSession.copy(
                            accessToken = newAccessToken,
                            refreshToken = newRefreshToken
                        )
                        sessionStorage.saveSession(updatedSession)
                        true
                    } else {
                        // Server returned success status but token was empty/whitespace
                        sessionStorage.clearSession()
                        false
                    }
                }
                else -> {
                    // 5xx server error, etc: do not clear session (not expired or revoked)
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network error / offline / timeout:
            // "Cerrar sesión solo si el refresh expira o es revocado por el servidor"
            // Preserves session for offline corral capture!
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
         * Helper to create an authenticated HttpClient with TokenRefreshInterceptor pre-installed.
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
            return client
        }
    }
}
