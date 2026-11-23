package com.agrotech.oviapp.android.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agrotech.oviapp.notifications.DeviceTokenRegistration
import java.util.concurrent.TimeUnit

/** Envía el token FCM pendiente cuando hay red; si el servidor falla, WorkManager reintenta con backoff. */
class DeviceTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = Notifications.session
        val accountId = if (session.accessToken() != null) session.accountId() else null
        return when (Notifications.registrar(applicationContext).registerIfNeeded(accountId)) {
            is DeviceTokenRegistration.RetryLater -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "register-device-token"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeviceTokenWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
