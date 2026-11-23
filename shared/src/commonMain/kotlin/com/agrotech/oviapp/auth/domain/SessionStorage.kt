package com.agrotech.oviapp.auth.domain

interface SessionStorage {
    fun saveSession(session: AuthSession)
    fun getSession(): AuthSession?
    fun clearSession()
    fun hasSession(): Boolean

    /**
     * Atomically updates the stored session only if the current stored session
     * still belongs to [expectedSessionId], [expectedRefreshToken], and optionally [expectedAccessToken].
     */
    fun updateSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?,
        expectedAccessToken: String? = null,
        transform: (AuthSession) -> AuthSession
    ): Boolean

    /**
     * Atomically clears the stored session only if the current stored session
     * still belongs to [expectedSessionId], [expectedRefreshToken], and optionally [expectedAccessToken].
     */
    fun clearSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?,
        expectedAccessToken: String? = null
    ): Boolean
}
