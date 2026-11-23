package com.agrotech.oviapp.notifications

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cuerpo de POST /api/v1/notifications/device-token (BE-015). */
@Serializable
data class DeviceTokenRequest(
    val token: String,
    val platform: String = PLATFORM_ANDROID,
) {
    companion object {
        const val PLATFORM_ANDROID = "ANDROID"
    }
}

interface DeviceTokenApi {
    suspend fun register(request: DeviceTokenRequest)
}

/**
 * Dónde se recuerdan el último token FCM recibido, el último que el servidor aceptó y la
 * generación de sesión (sube en cada logout) para descartar registros que terminan tarde.
 */
interface DeviceTokenStore {
    var pendingToken: String?
    var registeredToken: String?

    /** Cuenta para la que se registró [registeredToken]: una marca de otra cuenta nunca cuenta como registro. */
    var registeredAccount: String?
    var sessionGeneration: Long
}

class InMemoryDeviceTokenStore : DeviceTokenStore {
    override var pendingToken: String? = null
    override var registeredToken: String? = null
    override var registeredAccount: String? = null
    override var sessionGeneration: Long = 0
}

sealed interface DeviceTokenRegistration {
    data object Registered : DeviceTokenRegistration
    data object AlreadyRegistered : DeviceTokenRegistration
    data object NoToken : DeviceTokenRegistration
    data object NotLoggedIn : DeviceTokenRegistration
    data class RetryLater(val cause: Throwable) : DeviceTokenRegistration
}

/**
 * Registra el token FCM del celular en el backend para que el WeaningAlertScheduler (BE-015)
 * pueda avisar los destetes. Solo envía cuando hay sesión y el token cambió; si no hay señal
 * o el servidor falla, el token queda pendiente y el siguiente intento lo vuelve a mandar.
 */
class DeviceTokenRegistrar(
    private val api: DeviceTokenApi,
    private val store: DeviceTokenStore,
) {
    fun onNewToken(token: String) {
        store.pendingToken = token
    }

    /** El token es por usuario: al cerrar sesión, el siguiente usuario debe registrarlo de nuevo. */
    suspend fun onLogout() {
        sessionLock.withLock {
            store.sessionGeneration += 1
            store.registeredAccount = null
            store.registeredToken = null
        }
    }

    /**
     * [accountId] es la cuenta de la sesión activa (null = sin sesión). La marca de "ya registrado" es por
     * token **y** cuenta: aunque un logout no alcance a borrarla (se cancela o Android mata el proceso), el
     * siguiente usuario no la confunde con la suya y vuelve a registrar el celular.
     */
    suspend fun registerIfNeeded(accountId: String?): DeviceTokenRegistration {
        val token = store.pendingToken ?: return DeviceTokenRegistration.NoToken
        if (accountId == null) return DeviceTokenRegistration.NotLoggedIn
        // La marca y la generación se leen bajo el candado: si hay un logout esperando, corre antes y esta
        // revisión ya ve la marca borrada (si no, el siguiente usuario creería que ya está registrado).
        val generation = sessionLock.withLock {
            if (token == store.registeredToken && accountId == store.registeredAccount) {
                return DeviceTokenRegistration.AlreadyRegistered
            }
            store.sessionGeneration
        }
        return try {
            api.register(DeviceTokenRequest(token = token))
            // Si hubo logout mientras viajaba la petición, ese registro era del usuario anterior:
            // no se marca, para que el siguiente usuario sí registre el celular. La revisión y la
            // escritura van bajo el mismo candado que onLogout, así un logout no puede colarse entre ellas.
            sessionLock.withLock {
                if (store.sessionGeneration == generation) {
                    store.registeredAccount = accountId
                    store.registeredToken = token
                }
            }
            DeviceTokenRegistration.Registered
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            DeviceTokenRegistration.RetryLater(failure)
        }
    }

    private companion object {
        /**
         * Compartido por todas las instancias: la app crea un registrador por llamada
         * (`Notifications.registrar`) y todos escriben el mismo store desde hilos distintos.
         */
        val sessionLock = Mutex()
    }
}

class DeviceTokenRegistrationException(message: String) : Exception(message)

class KtorDeviceTokenApi(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val accessToken: suspend () -> String?,
) : DeviceTokenApi {
    // encodeDefaults: sin esto "platform" (valor por defecto) no viajaría en el cuerpo.
    private val requestJson = Json { encodeDefaults = true }
    private val endpoint = baseUrl.trimEnd('/') + "/api/v1/notifications/device-token"

    override suspend fun register(request: DeviceTokenRequest) {
        val jwt = accessToken() ?: throw DeviceTokenRegistrationException("No hay sesión para registrar el dispositivo.")
        val response: HttpResponse = httpClient.post(endpoint) {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody(requestJson.encodeToString(request))
        }
        if (!response.status.isSuccess()) {
            throw DeviceTokenRegistrationException("El servidor respondió ${response.status.value} al registrar el dispositivo.")
        }
    }
}

/** Datos que manda la notificación push de destete (BE-015) y que viajan en el intent al tocarla. */
data class WeaningAlert(
    val animalId: String,
    val ranchId: String,
    val folioInterno: String?,
) {
    /**
     * Identidad estable rancho/animal para el tag de la notificación: el mismo cordero reemplaza su aviso
     * anterior y dos corderos distintos nunca chocan. Cada parte se escapa antes de unirlas para que un
     * "/" dentro de un id no vuelva ambigua la identidad.
     */
    val identity: String get() = "${escape(ranchId)}/${escape(animalId)}"

    private fun escape(part: String) = part.replace("%", "%25").replace("/", "%2F")

    fun toData(): Map<String, String> = buildMap {
        put(KEY_TYPE, TYPE_WEANING_DUE)
        put(KEY_ANIMAL_ID, animalId)
        put(KEY_RANCH_ID, ranchId)
        folioInterno?.let { put(KEY_FOLIO, it) }
    }

    companion object {
        const val TYPE_WEANING_DUE = "WEANING_DUE"
        const val KEY_TYPE = "type"
        const val KEY_ANIMAL_ID = "animalId"
        const val KEY_RANCH_ID = "ranchId"
        const val KEY_FOLIO = "folio"

        fun fromData(data: Map<String, String?>): WeaningAlert? {
            if (data[KEY_TYPE] != TYPE_WEANING_DUE) return null
            val animalId = data[KEY_ANIMAL_ID]?.takeIf { it.isNotBlank() } ?: return null
            val ranchId = data[KEY_RANCH_ID]?.takeIf { it.isNotBlank() } ?: return null
            return WeaningAlert(animalId, ranchId, data[KEY_FOLIO]?.takeIf { it.isNotBlank() })
        }
    }
}
