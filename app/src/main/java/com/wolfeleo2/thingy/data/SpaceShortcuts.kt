package com.wolfeleo2.thingy.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wolfeleo2.thingy.MainActivity
import com.wolfeleo2.thingy.R

/**
 * Publishes the user's spaces as Direct Share targets, so another app's share sheet can offer
 * "Thingy › Recipes" and drop the photo straight into that space.
 *
 * The shortcut id IS the space id: when the user picks one of these targets the system delivers
 * the share intent with [Intent.EXTRA_SHORTCUT_ID] set, which is all MainActivity needs to route
 * the save. Tapping one from the launcher's long-press menu instead opens that space, via the
 * [EXTRA_SPACE_ID] extra on the shortcut's own intent.
 *
 * Must be paired with the `<share-target>` in res/xml/shortcuts.xml — the category below is what
 * links a published shortcut to that declaration.
 */
object SpaceShortcuts {
    const val CATEGORY = "com.wolfeleo2.thingy.category.SAVE_TO_SPACE"
    const val EXTRA_SPACE_ID = "com.wolfeleo2.thingy.extra.SPACE_ID"

    /** Android shows at most a handful of Direct Share rows anyway; more just costs churn. */
    const val MAX = 4

    fun publish(context: Context, spaces: List<Space>) {
        val shortcuts = shortcutEntries(spaces).map { (id, name) ->
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_space))
                .setCategories(setOf(CATEGORY))
                .setLongLived(true) // required for Direct Share ranking to remember this target
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(EXTRA_SPACE_ID, id),
                )
                .build()
        }
        // Best-effort: a rate-limited or unavailable launcher must never break sign-in.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** Sign-out: don't leave one account's space names on another's share sheet. */
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}

/**
 * (shortcut id, label) for the spaces worth publishing — id is the space id, which is how a share
 * comes back to us. Spaces with no id are dropped: a shortcut needs a stable id, and an unsaved
 * space would produce one that can never be resolved.
 */
internal fun shortcutEntries(spaces: List<Space>): List<Pair<String, String>> =
    spaces.filter { it.id.isNotBlank() }
        .take(SpaceShortcuts.MAX)
        .map { it.id to it.name.trim().ifBlank { "Space" } }
