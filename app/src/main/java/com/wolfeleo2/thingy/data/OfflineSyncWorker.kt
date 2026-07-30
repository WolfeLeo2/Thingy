package com.wolfeleo2.thingy.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs [OfflineImageSyncer] on a schedule instead of on every app launch.
 *
 * It's a long, network-bound job — a Firestore query for every image/video/link item, then a
 * throttled download per missing file — and running it at startup made it contend with the first
 * Firestore listener for the network. Here it gets a CONNECTED constraint, so on an offline device
 * it doesn't run at all rather than failing slowly per item, and it keeps working while the app is
 * closed, which is the point of the "have every image on disk" promise.
 */
class OfflineSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // Always success: individual downloads already self-heal on the next period, and a Result.retry
    // here would just re-run the whole scan for the sake of one 404'd image.
    override suspend fun doWork(): Result {
        runCatching { OfflineImageSyncer(applicationContext).run() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "offline_image_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
