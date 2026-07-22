package com.wolfeleo2.thingy.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wolfeleo2.thingy.MainActivity
import com.wolfeleo2.thingy.R
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.displayTitle
import kotlinx.coroutines.flow.firstOrNull
import java.util.Calendar

class ResurfaceWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemRepository = ItemRepository()
        val settingsRepository = SettingsRepository(context)

        val items = itemRepository.items().firstOrNull().orEmpty()
        if (items.isEmpty()) return Result.success()

        val today = Calendar.getInstance()
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val todayYear = today.get(Calendar.YEAR)

        // 1. Check for exact anniversary items (saved on same day/month in a previous year)
        var resurfaceTarget = items.firstOrNull { item ->
            item.createdAt?.let { date ->
                val cal = Calendar.getInstance().apply { time = date }
                cal.get(Calendar.MONTH) == todayMonth &&
                        cal.get(Calendar.DAY_OF_MONTH) == todayDay &&
                        cal.get(Calendar.YEAR) < todayYear
            } ?: false
        }

        // 2. Fallback for newer installs: pick a random item created at least 14 days ago
        if (resurfaceTarget == null) {
            val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
            resurfaceTarget = items.filter { (it.createdAt?.time ?: 0L) < fourteenDaysAgo }.randomOrNull()
        }

        val target = resurfaceTarget ?: return Result.success()

        // Set resurfaced item ID in DataStore settings
        settingsRepository.setResurfacedItemId(target.id)

        // Post system notification if permissions are granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success()
            }
        }

        ReminderManager.setupNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderManager.EXTRA_OPEN_ITEM_ID, target.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            target.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✨ On this day / Remember this?")
            .setContentText("You saved “${target.displayTitle()}” — tap to take a look!")
            .setStyle(NotificationCompat.BigTextStyle().bigText("You saved “${target.displayTitle()}” — tap to take a look!"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(target.id.hashCode(), notification)
        }

        return Result.success()
    }
}
