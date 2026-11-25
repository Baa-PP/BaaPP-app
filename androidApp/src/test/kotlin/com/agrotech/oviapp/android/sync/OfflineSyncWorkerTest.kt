package com.agrotech.oviapp.android.sync

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineSyncWorkerTest {

    @Test
    fun buildWorkRequestRequiresConnectedNetworkAndExponentialBackoff() {
        val workSpec = OfflineSyncWorker.buildWorkRequest().workSpec

        assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
        assertEquals(OfflineSyncWorker.BACKOFF_DELAY_SECONDS * 1000L, workSpec.backoffDelayDuration)
    }
}
