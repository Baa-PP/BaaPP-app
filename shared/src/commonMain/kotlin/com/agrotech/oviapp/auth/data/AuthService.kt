package com.agrotech.oviapp.auth.data

import com.agrotech.oviapp.auth.domain.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

interface AuthService : AutoCloseable {
    suspend fun login(username: String, password: String): AuthSession
    override fun close() {}
}

class KtorAuthService internal constructor(
    private val baseUrl: String,
    private val client: HttpClient
) : AuthService {

    constructor(baseUrl: String = DEFAULT_BASE_URL) : this(baseUrl, createDefaultHttpClient())
    internal constructor(client: HttpClient) : this(DEFAULT_BASE_URL, client)

    init {
        enforceHttps(baseUrl)
    }

    override suspend fun login(username: String, password: String): AuthSession {
        val trimmedUser = username.trim()
        val sanitizedUrl = baseUrl.trimEnd('/') + "/auth/login"
        enforceHttps(sanitizedUrl)

        val response = try {
            client.post(sanitizedUrl) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(LoginRequest(username = trimmedUser, password = password))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthNetworkException("No se pudo conectar con el servidor. Revisa tu conexión a internet.", e)
        }

        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw InvalidCredentialsException(
                "No pudimos abrir tu rancho. Revisa tu usuario y contraseña; si sigue fallando, pide apoyo al administrador de tu asociación."
            )
        }

        if (!response.status.isSuccess()) {
            throw AuthServerException("Error al autenticar en el servidor (HTTP ${response.status.value}).")
        }

        val loginResponse = response.body<LoginResponse>()
        val token = loginResponse.resolveAccessToken()?.takeIf { it.isNotBlank() }
            ?: throw AuthServerException("El servidor no devolvió un token de acceso válido.")

        return AuthSession(
            accessToken = token,
            refreshToken = loginResponse.resolveRefreshToken(),
            username = loginResponse.resolveUsername(trimmedUser),
            role = loginResponse.resolveRole(),
            ranchId = loginResponse.resolveRanchId(),
            permissions = loginResponse.resolvePermissions()
        )
    }

    override fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://du6aq4me7fp0q.cloudfront.net/api/v1"

        fun enforceHttps(url: String) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("Security violation: Solo se permiten conexiones HTTPS ($url)")
            }
        }

        fun createDefaultHttpClient(): HttpClient {
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
    }
}

open class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InvalidCredentialsException(message: String) : AuthException(message)
class AuthNetworkException(message: String, cause: Throwable? = null) : AuthException(message, cause)
class AuthServerException(message: String, cause: Throwable? = null) : AuthException(message, cause)
