package com.agrotech.oviapp.auth.domain

import com.agrotech.oviapp.auth.data.AuthService
import kotlinx.coroutines.CancellationException

class AuthRepository(
    private val authService: AuthService,
    private val sessionStorage: SessionStorage
) : AutoCloseable {

    fun getCachedSession(): AuthSession? = sessionStorage.getSession()

    fun hasValidSession(): Boolean = sessionStorage.hasSession()

    suspend fun login(username: String, password: String): Result<AuthSession> {
        return try {
            val session = authService.login(username, password)
            sessionStorage.saveSession(session)
            Result.success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun logout() {
        sessionStorage.clearSession()
    }

    override fun close() {
        authService.close()
    }
}
