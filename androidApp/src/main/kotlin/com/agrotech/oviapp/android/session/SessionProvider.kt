package com.agrotech.oviapp.android.session

/**
 * Fuente del JWT del usuario. El login real llega con APP-02/APP-03; mientras tanto no hay
 * sesión, así que el token FCM se guarda como pendiente y se registra en cuanto alguien entre.
 */
interface SessionProvider {
    suspend fun accessToken(): String?

    /** Identidad de la cuenta activa; el registro del token FCM se marca por cuenta. */
    suspend fun accountId(): String?
}

object NoSessionYet : SessionProvider {
    override suspend fun accessToken(): String? = null
    override suspend fun accountId(): String? = null
}
