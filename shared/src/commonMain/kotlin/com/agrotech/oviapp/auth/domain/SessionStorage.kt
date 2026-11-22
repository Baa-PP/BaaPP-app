package com.agrotech.oviapp.auth.domain

interface SessionStorage {
    fun saveSession(session: AuthSession)
    fun getSession(): AuthSession?
    fun clearSession()
    fun hasSession(): Boolean

    /**
     * Atomically updates the stored session only if the current stored session
     * still belongs to [expectedSessionId].
     */
    fun updateSessionIfMatchesGeneration(
        expectedSessionId: String,
        transform: (AuthSession) -> AuthSession
    ): Boolean {
        val current = getSession() ?: return false
        if (current.sessionId != expectedSessionId) return false
        saveSession(transform(current))
        return true
    }

    /**
     * Atomically clears the stored session only if the current stored session
     * still belongs to [expectedSessionId].
     */
    fun clearSessionIfMatchesGeneration(expectedSessionId: String): Boolean {
        val current = getSession() ?: return false
        if (current.sessionId != expectedSessionId) return false
        clearSession()
        return true
    }
}
