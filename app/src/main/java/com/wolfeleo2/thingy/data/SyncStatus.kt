package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * User-visible reporting for writes that aren't awaited by their caller.
 *
 * Most repository writes are `await()`ed, so a failure propagates to the call site and the UI can
 * react. The fire-and-forget ones — item creation (kept off the critical path so the camera doesn't
 * block on a server round-trip) and the background Cloudinary upload — had nowhere to report to and
 * only reached logcat. This is that somewhere: AppRoot collects it into a snackbar.
 *
 * Note this is for *rejections*, not slowness. Firestore retries a queued write indefinitely, so a
 * plain lack of network never lands here — it shows up as the per-item pending badge instead.
 */
object SyncStatus {
    // Replay 0, drop-oldest: a failure the user wasn't around to see is stale, not a backlog.
    private val _failures = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val failures: SharedFlow<String> = _failures

    fun report(message: String, cause: Throwable? = null) {
        Log.w(TAG, message, cause)
        _failures.tryEmit(message)
    }

    /** Attaches failure reporting to a write nobody is awaiting. Returns the task for chaining. */
    fun <T> Task<T>.reportFailure(message: String): Task<T> =
        addOnFailureListener { report(message, it) }

    private const val TAG = "ThingySync"
}
