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
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.displayTitle
import com.wolfeleo2.thingy.ui.previewModel
import com.wolfeleo2.thingy.ui.widget.ThingyWidget
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

        val target = pickResurfaceTarget(items, System.currentTimeMillis()) ?: return Result.success()

        // Set resurfaced item ID in DataStore settings
        settingsRepository.setResurfacedItemId(target.id)

        // Cache a flat snapshot for the home-screen widget — it can't read Firestore itself — and
        // repaint it. Local file only: the widget has no network path to a Cloudinary URL.
        settingsRepository.setWidgetCard(
            itemId = target.id,
            title = target.displayTitle(),
            thumbPath = (target.previewModel(context) as? java.io.File)?.absolutePath,
        )
        ThingyWidget.refresh(context)

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

/** Older than this and an item is fair game for the random fallback. */
private const val RESURFACE_MIN_AGE_MS = 14L * 24 * 60 * 60 * 1000

/**
 * Today's "remember this?" pick, shown both as a notification and on the home-screen widget.
 *
 * An exact anniversary (same day and month, an earlier year) always wins; failing that — which is
 * every install younger than a year — any save at least [RESURFACE_MIN_AGE_MS] old, at random, so
 * the feature isn't dead for new users. Null when nothing qualifies; the caller then does nothing
 * rather than resurfacing something the user saved this morning.
 *
 * [items] is expected newest-first, as [ItemRepository.items] returns it.
 */
internal fun pickResurfaceTarget(items: List<Item>, nowMs: Long): Item? {
    val today = Calendar.getInstance().apply { timeInMillis = nowMs }
    val anniversary = items.firstOrNull { item ->
        item.createdAt?.let { date ->
            val cal = Calendar.getInstance().apply { time = date }
            cal.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                cal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH) &&
                cal.get(Calendar.YEAR) < today.get(Calendar.YEAR)
        } ?: false
    }
    if (anniversary != null) return anniversary
    // createdAt is a @ServerTimestamp: null means the write hasn't been acked yet, i.e. the item is
    // seconds old — the opposite of resurfaceable. Treating null as epoch (the old `?: 0L`) made a
    // just-saved item eligible as a "memory".
    return items.filter { it.createdAt != null && it.createdAt.time < nowMs - RESURFACE_MIN_AGE_MS }
        .randomOrNull()
}
