package com.wolfeleo2.thingy.lib

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.net.toUri
import com.wolfeleo2.thingy.data.IntentKind

// Maps an item's intent to a platform action (PRD §7). Mirrors Amber's lib/intents.ts.
fun runIntent(context: Context, kind: String, value: String) {
    runCatching {
        when (kind) {
            IntentKind.OPEN_URL.wire -> view(context, value.toUri())
            IntentKind.WEB_SEARCH.wire ->
                view(context, "https://www.google.com/search?q=${Uri.encode(value)}".toUri())
            IntentKind.OPEN_MAPS.wire -> view(context, "geo:0,0?q=${Uri.encode(value)}".toUri())
            IntentKind.CALL.wire -> context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${value.filter { it.isDigit() || it == '+' }}".toUri()))
            IntentKind.MESSAGE.wire -> context.startActivity(Intent(Intent.ACTION_SENDTO, "smsto:${value.filter { it.isDigit() || it == '+' }}".toUri()))
            IntentKind.EMAIL.wire -> context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$value".toUri()))
            IntentKind.ADD_EVENT.wire -> context.startActivity(
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, value),
            )
            IntentKind.COPY.wire -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Thingy", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun view(context: Context, uri: Uri) =
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
