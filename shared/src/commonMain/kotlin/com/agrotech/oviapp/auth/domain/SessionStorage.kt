package com.agrotech.oviapp.auth.domain

interface SessionStorage {
    fun saveSession(session: AuthSession)
    fun getSession(): AuthSession?
    fun clearSession()
    fun hasSession(): Boolean

    /**
     * Atomically updates the stored session only if the current stored session
     * still belongs to [expectedSessionId] and [expectedRefreshToken].
     */
    fun updateSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?,
        transform: (AuthSession) -> AuthSession
    ): Boolean

    /**
     * Atomically clears the stored session only if the current stored session
     * still belongs to [expectedSessionId] and [expectedRefreshToken].
     */
    fun clearSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?
    ): Boolean
}
