package com.wolfeleo2.thingy.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.size
import com.wolfeleo2.thingy.MainActivity
import com.wolfeleo2.thingy.R
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.WidgetCard
import com.wolfeleo2.thingy.reminders.ReminderManager
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Home-screen widget: today's resurfaced save, plus a quick-capture button.
 *
 * Reads only the flat snapshot the resurfacing worker leaves in DataStore
 * ([SettingsRepository.widgetCard]) — a widget is rendered outside any signed-in foreground
 * session, so it must never touch Firestore. Stale-but-present beats empty-and-correct here.
 */
class ThingyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val card = SettingsRepository(context).widgetCard.first()
        val thumb = card?.thumbPath?.let { decodeThumb(it) }
        provideContent { GlanceTheme { WidgetBody(card, thumb) } }
    }

    @Composable
    private fun WidgetBody(card: WidgetCard?, thumb: Bitmap?) {
        val context = LocalContext.current
        val openItem = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(ReminderManager.EXTRA_OPEN_ITEM_ID, card?.itemId)
        val openCamera = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_OPEN_CAMERA, true)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(14.dp)
                .let { if (card != null) it.clickable(actionStartActivity(openItem)) else it },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = if (card != null) "On this day" else "Thingy",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_capture),
                    contentDescription = "Capture a thingy",
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    modifier = GlanceModifier
                        .size(32.dp)
                        .cornerRadius(16.dp)
                        .background(GlanceTheme.colors.primaryContainer)
                        .padding(6.dp)
                        .clickable(actionStartActivity(openCamera)),
                )
            }

            Spacer(GlanceModifier.height(10.dp))

            if (thumb != null) {
                Image(
                    provider = ImageProvider(thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(16.dp),
                )
                Spacer(GlanceModifier.height(8.dp))
            }

            Text(
                text = card?.title?.takeIf { it.isNotBlank() } ?: "Nothing to look back on yet — save something.",
                maxLines = 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_CAMERA = "com.wolfeleo2.thingy.extra.OPEN_CAMERA"

        /** RemoteViews bitmaps cross a Binder transaction — decode small or the widget silently blanks. */
        private const val THUMB_MAX_PX = 512

        fun decodeThumb(path: String): Bitmap? {
            val file = File(path).takeIf { it.exists() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (longest > THUMB_MAX_PX) Integer.highestOneBit(longest / THUMB_MAX_PX) else 1
            }
            return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
        }

        /** Called by the resurfacing worker once it has written a fresh snapshot. */
        suspend fun refresh(context: Context) {
            runCatching { ThingyWidget().updateAll(context) }
        }
    }
}

class ThingyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThingyWidget()
}
