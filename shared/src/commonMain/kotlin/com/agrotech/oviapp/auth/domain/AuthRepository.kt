package com.agrotech.oviapp.auth.domain

import com.agrotech.oviapp.auth.data.AuthService

class AuthRepository(
    private val authService: AuthService,
    private val sessionStorage: SessionStorage
) {
    fun getCachedSession(): AuthSession? = sessionStorage.getSession()

    fun hasValidSession(): Boolean = sessionStorage.hasSession()

    suspend fun login(username: String, password: String): Result<AuthSession> {
        return try {
            val session = authService.login(username, password)
            sessionStorage.saveSession(session)
            Result.success(session)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun logout() {
        sessionStorage.clearSession()
    }
}
