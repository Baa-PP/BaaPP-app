package com.agrotech.oviapp.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agrotech.oviapp.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Worker de sincronización offline-first (APP-21).
 * Requiere [NetworkType.CONNECTED] para dispararse automáticamente al recuperar señal
 * y usa [BackoffPolicy.EXPONENTIAL] ante fallos de red o servidor.
 */
class OfflineSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = try {
            OfflineSyncDependencies.syncNow(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }

        return when (outcome) {
            is SyncOutcome.NothingToSync -> Result.success()
            is SyncOutcome.NotAuthenticated -> Result.success()
            is SyncOutcome.Success -> if (outcome.failedCount > 0) Result.retry() else Result.success()
            is SyncOutcome.RetryLater -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "offline-sync-batch"
        const val BACKOFF_DELAY_SECONDS = 30L

        internal fun buildWorkRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Programa sincronización automática al reconectar; si ya hay una ejecución en curso,
         * encadena una siguiente para incluir mutaciones confirmadas después del snapshot actual.
         */
        fun enqueueOnReconnect(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildWorkRequest(),
            )
        }

        /**
         * Reemplaza cualquier trabajo pendiente para ejecutar el lote en cuanto haya conexión.
         */
        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                buildWorkRequest(),
            )
        }
    }
}
