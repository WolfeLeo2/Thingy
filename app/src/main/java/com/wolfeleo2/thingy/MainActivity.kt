package com.wolfeleo2.thingy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.AuthRepository
import com.wolfeleo2.thingy.data.ColorSource
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.reminders.ReminderManager
import com.wolfeleo2.thingy.ui.AppRoot
import com.wolfeleo2.thingy.ui.theme.ThingyTheme

class MainActivity : ComponentActivity() {
    // Content shared into the app (ACTION_SEND); consumed by AppRoot once signed in + onboarded.
    private val sharedText = mutableStateOf<String?>(null)
    private val sharedImages = mutableStateOf<List<Uri>>(emptyList())
    private val openItemId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consume(intent)

        val settings = SettingsRepository(applicationContext)
        val auth = AuthRepository()
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val serverClientId = if (resId != 0) getString(resId) else null

        setContent {
            val colorSource by settings.colorSource.collectAsStateWithLifecycle(ColorSource.DYNAMIC)
            ThingyTheme(colorSource = colorSource) {
                AppRoot(
                    auth = auth,
                    settings = settings,
                    serverClientId = serverClientId,
                    sharedText = sharedText.value,
                    onSharedConsumed = { sharedText.value = null },
                    sharedImages = sharedImages.value,
                    onImagesConsumed = { sharedImages.value = emptyList() },
                    openItemId = openItemId.value,
                    onOpenItemConsumed = { openItemId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(ReminderManager.EXTRA_OPEN_ITEM_ID)?.let { id ->
            openItemId.value = id
        }
        val type = intent.type.orEmpty()
        // An image share often ALSO carries the page URL as EXTRA_TEXT — treat it as an image
        // ONLY (image wins) so sharing a picture doesn't secretly also save a link.
        if (type.startsWith("image/")) {
            val uris = when (intent.action) {
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) } ?: emptyList()
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
                else -> emptyList()
            }
            if (uris.isNotEmpty()) { sharedImages.value = uris; sharedText.value = null }
        } else if (type == "text/plain" && intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let { sharedText.value = it }
        }
    }
}
