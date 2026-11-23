package com.agrotech.oviapp.android.notifications

import android.content.Context
import com.agrotech.oviapp.android.BuildConfig
import com.agrotech.oviapp.android.session.NoSessionYet
import com.agrotech.oviapp.android.session.SessionProvider
import com.agrotech.oviapp.notifications.DeviceTokenRegistrar
import com.agrotech.oviapp.notifications.KtorDeviceTokenApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Composición manual de las piezas de notificaciones hasta que el proyecto adopte un contenedor DI. */
object Notifications {
    /** MainAppViewModel lo reemplaza por la sesión real y llama a registrar(...).onLogout() al salir. */
    @Volatile
    var session: SessionProvider = NoSessionYet

    private val httpClient by lazy { HttpClient(OkHttp) }

    fun registrar(context: Context): DeviceTokenRegistrar = DeviceTokenRegistrar(
        api = KtorDeviceTokenApi(httpClient, BuildConfig.API_BASE_URL, accessToken = { session.accessToken() }),
        store = SharedPrefsDeviceTokenStore(context.applicationContext),
    )
}
