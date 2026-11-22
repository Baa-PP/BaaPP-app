package com.agrotech.oviapp.auth.domain

interface SessionStorage {
    fun saveSession(session: AuthSession)
    fun getSession(): AuthSession?
    fun clearSession()
    fun hasSession(): Boolean
}
