package com.wolfeleo2.thingy.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object ReminderManager {
    const val CHANNEL_ID = "thingy_reminders"
    const val CHANNEL_NAME = "Reminders & Resurfacing"
    const val EXTRA_OPEN_ITEM_ID = "open_item_id"
    private const val RESURFACE_WORK_NAME = "daily_resurface_check"

    fun setupNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for snoozed thingies and anniversary resurfaces"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun scheduleSnooze(
        context: Context,
        itemId: String,
        title: String,
        snippet: String?,
        targetEpochMs: Long
    ) {
        setupNotificationChannel(context)
        val now = System.currentTimeMillis()
        val delayMs = (targetEpochMs - now).coerceAtLeast(1000L)

        val inputData = Data.Builder()
            .putString("itemId", itemId)
            .putString("title", title)
            .putString("snippet", snippet ?: "")
            .build()

        val request = OneTimeWorkRequestBuilder<RemindWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag("snooze_$itemId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "snooze_$itemId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelSnooze(context: Context, itemId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("snooze_$itemId")
    }

    fun scheduleDailyResurface(context: Context) {
        setupNotificationChannel(context)

        // Run once every 24h, starting around 9:00 AM next morning
        val now = Calendar.getInstance()
        val nextRun = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val initialDelay = nextRun.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<ResurfaceWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RESURFACE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
