package com.agrotech.oviapp.auth.domain

import com.agrotech.oviapp.auth.data.AuthService
import kotlinx.coroutines.CancellationException

internal expect class AuthLock() {
    inline fun <T> withLock(block: () -> T): T
}

class AuthRepository(
    private val authService: AuthService,
    private val sessionStorage: SessionStorage
) : AutoCloseable {

    private val stateLock = AuthLock()
    private var authGeneration: Long = 0L

    fun getCachedSession(): AuthSession? = sessionStorage.getSession()

    fun hasValidSession(): Boolean = sessionStorage.hasSession()

    suspend fun login(username: String, password: String): Result<AuthSession> {
        val myGeneration = stateLock.withLock { ++authGeneration }
        return try {
            val session = authService.login(username, password)
            val saved = stateLock.withLock {
                if (authGeneration == myGeneration) {
                    sessionStorage.saveSession(session)
                    true
                } else {
                    false
                }
            }
            if (saved) {
                Result.success(session)
            } else {
                Result.failure(CancellationException("Inicio de sesión reemplazado por una operación más reciente."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun logout() {
        stateLock.withLock {
            authGeneration++
            sessionStorage.clearSession()
        }
    }

    override fun close() {
        authService.close()
    }
}
