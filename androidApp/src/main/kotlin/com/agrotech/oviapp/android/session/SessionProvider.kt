package com.agrotech.oviapp.android.session

/**
 * Fuente del JWT del usuario. El login real llega con APP-02/APP-03; mientras tanto no hay
 * sesión, así que el token FCM se guarda como pendiente y se registra en cuanto alguien entre.
 */
interface SessionProvider {
    suspend fun accessToken(): String?
}

object NoSessionYet : SessionProvider {
    override suspend fun accessToken(): String? = null
}
