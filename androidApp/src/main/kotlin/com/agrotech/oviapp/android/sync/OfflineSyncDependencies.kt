package com.agrotech.oviapp.android.sync

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.agrotech.oviapp.android.BuildConfig
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.data.TokenRefreshInterceptor
import com.agrotech.oviapp.auth.domain.SessionStorage
import com.agrotech.oviapp.db.DatabaseDriverFactory
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.sync.KtorSyncBatchApi
import com.agrotech.oviapp.sync.OfflineSyncEngine
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncOutcome
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Composición manual de sincronización offline-first para Android (APP-21).
 * Reutiliza el almacenamiento cifrado de sesión y el refresh JWT de `:shared`.
 */
object OfflineSyncDependencies {
    private val lock = Any()
    private val syncGate = Mutex()

    @Volatile
    private var cachedSessionStorage: SessionStorage? = null

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    @Volatile
    private var cachedDriver: SqlDriver? = null

    @Volatile
    private var cachedDatabase: OviAppDatabase? = null

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

    private fun database(context: Context): OviAppDatabase =
        cachedDatabase ?: synchronized(lock) {
            cachedDatabase ?: run {
                val driver = cachedDriver ?: DatabaseDriverFactory(context.applicationContext)
                    .createDriver()
                    .also { cachedDriver = it }
                OviAppDatabase(driver).also { cachedDatabase = it }
            }
        }

    private fun activeAccountId(context: Context): String? =
        sessionStorage(context).getSession()?.username?.takeIf { it.isNotBlank() }

    fun hasActiveSession(context: Context): Boolean =
        !sessionStorage(context).getSession()?.accessToken.isNullOrBlank() &&
            activeAccountId(context) != null

    fun pendingCount(context: Context): Long =
        PendingSyncQueue(database(context)).pendingCountForAccount(activeAccountId(context))

    fun unownedPendingCount(context: Context): Long =
        PendingSyncQueue(database(context)).unownedPendingCount()

    fun claimUnownedPendingForActiveAccount(context: Context): Long {
        val accountId = activeAccountId(context) ?: return 0L
        return PendingSyncQueue(database(context)).claimUnownedPendingForAccount(accountId)
    }

    fun engine(context: Context): OfflineSyncEngine {
        val appContext = context.applicationContext
        val account = activeAccountId(appContext)
        return createEngineForAccount(appContext, account)
    }

    private fun createEngineForAccount(appContext: Context, expectedAccount: String?): OfflineSyncEngine {
        val storage = sessionStorage(appContext)
        val queue = PendingSyncQueue(database(appContext))
        val api = KtorSyncBatchApi(
            httpClient = httpClient(appContext),
            baseUrl = apiBaseUrl,
            // Verifica antes de cada lote que la sesión almacenada siga perteneciendo a la cuenta del snapshot.
            accessToken = {
                val session = storage.getSession()
                val currentAccount = session?.username?.takeIf { it.isNotBlank() }
                if (expectedAccount != null && currentAccount == expectedAccount) {
                    session.accessToken
                } else {
                    null
                }
            },
        )
        return OfflineSyncEngine(
            queue = queue,
            api = api,
            pendingProvider = { queue.pendingForAccount(expectedAccount) },
        )
    }

    suspend fun syncNow(context: Context): SyncOutcome = syncGate.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val snapshotAccount = activeAccountId(appContext)
                ?: return@withContext SyncOutcome.NotAuthenticated
            createEngineForAccount(appContext, snapshotAccount).syncPending(
                isAuthenticated = {
                    val session = sessionStorage(appContext).getSession()
                    !session?.accessToken.isNullOrBlank() &&
                        session?.username?.takeIf { it.isNotBlank() } == snapshotAccount
                },
            )
        }
    }

    fun resetSessionCache() {
        val clientToClose = synchronized(lock) {
            cachedSessionStorage = null
            cachedHttpClient.also { cachedHttpClient = null }
        }
        clientToClose?.close()
    }
}
