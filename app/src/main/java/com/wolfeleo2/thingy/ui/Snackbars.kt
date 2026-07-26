package com.wolfeleo2.thingy.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-wide confirmation snackbar. AppRoot provides it and runs it on AppRoot's own scope, above the
 * NavDisplay — two things that both matter:
 *
 * - It is safe to call and immediately leave. Deleting from the item detail page pops that screen;
 *   a bar launched on the screen's own rememberCoroutineScope would be cancelled before it rendered,
 *   and a host composed inside the screen would leave with it.
 * - It is fire-and-forget. showSnackbar suspends until the bar goes away, so awaiting it inline would
 *   stall whatever repository work follows the confirmation.
 */
val LocalNotify = staticCompositionLocalOf<(String) -> Unit> {
    error("No notifier — AppRoot provides LocalNotify")
}

/** "3 thingies" / "1 thingy" — one plural, so the snackbars and the dialogs agree. */
fun thingies(count: Int): String = "$count ${if (count == 1) "thingy" else "thingies"}"
