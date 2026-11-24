package com.agrotech.oviapp.android.notifications

import android.content.Context
import com.agrotech.oviapp.android.BuildConfig
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.data.TokenRefreshInterceptor
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import com.agrotech.oviapp.notifications.DeviceTokenApi
import com.agrotech.oviapp.notifications.DeviceTokenRegistrar
import com.agrotech.oviapp.notifications.DeviceTokenRequest
import com.agrotech.oviapp.notifications.KtorDeviceTokenApi
import io.ktor.client.HttpClient

/** Composición manual de notificaciones reutilizando el almacenamiento de sesión y el refresh JWT de :shared. */
object Notifications {
    private val lock = Any()
    @Volatile
    private var cachedSessionStorage: SessionStorage? = null
    @Volatile
    private var cachedHttpClient: HttpClient? = null

    private val apiBaseUrl: String = BuildConfig.API_BASE_URL
        .ifBlank { KtorAuthService.DEFAULT_BASE_URL }
        .trimEnd('/')
        .let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }

    private fun sessionStorage(context: Context): SessionStorage =
        cachedSessionStorage ?: synchronized(lock) {
            cachedSessionStorage ?: AndroidEncryptedSessionStorage(context.applicationContext)
                .also { cachedSessionStorage = it }
        }

    private fun httpClient(context: Context): HttpClient =
        cachedHttpClient ?: synchronized(lock) {
            cachedHttpClient ?: TokenRefreshInterceptor.createAuthenticatedClient(
                sessionStorage = sessionStorage(context),
                baseUrl = apiBaseUrl,
            ).also { cachedHttpClient = it }
        }

    fun currentSession(context: Context): AuthSession? = sessionStorage(context).getSession()

    fun resetSessionCache() {
        val clientToClose = synchronized(lock) {
            cachedSessionStorage = null
            cachedHttpClient.also { cachedHttpClient = null }
        }
        clientToClose?.close()
    }

    fun registrar(context: Context): DeviceTokenRegistrar {
        val appContext = context.applicationContext
        // Lazy: onNewToken() y onLogout() solo tocan SharedPreferences; KeyStore y Ktor solo se abren al enviar.
        val api = object : DeviceTokenApi {
            override suspend fun register(request: DeviceTokenRequest) {
                val storage = sessionStorage(appContext)
                KtorDeviceTokenApi(
                    httpClient = httpClient(appContext),
                    baseUrl = apiBaseUrl,
                    accessToken = { storage.getSession()?.accessToken },
                ).register(request)
            }
        }
        return DeviceTokenRegistrar(api = api, store = SharedPrefsDeviceTokenStore(appContext))
    }
}
