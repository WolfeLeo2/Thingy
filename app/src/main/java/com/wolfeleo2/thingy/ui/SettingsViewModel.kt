package com.wolfeleo2.thingy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfeleo2.thingy.BuildConfig
import com.wolfeleo2.thingy.data.AppUpdate
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the smart-search model download and the app-update check/download/install, both in
 * viewModelScope — scoped to AppRoot's ViewModelStoreOwner (the Activity), not to SettingsScreen's
 * composition. A rememberCoroutineScope() inside SettingsScreen gets cancelled the instant Nav3
 * pops Settings off the back stack, silently killing an in-flight download; this survives it.
 * SettingsScreen reads this as plain state and calls these functions — it owns none of this itself.
 */
class SettingsViewModel(
    context: Context,
    private val settings: SettingsRepository,
    private val itemRepository: ItemRepository,
    private val embedder: Embedder,
) : ViewModel() {
    private val updateChecker = UpdateChecker(context)

    // --- Smart search model download ---
    var smartSearchDownloading by mutableStateOf(false); private set
    var smartSearchDownloadFailed by mutableStateOf(false); private set
    var smartSearchModelReady by mutableStateOf(embedder.isReady()); private set
    var smartSearchDlBytes by mutableStateOf(0L); private set
    var smartSearchDlTotal by mutableStateOf(0L); private set

    fun setSmartSearch(on: Boolean) {
        viewModelScope.launch {
            settings.setSmartSearch(on)
            if (on && !embedder.isReady()) {
                smartSearchDownloading = true
                smartSearchDownloadFailed = false
                smartSearchDlBytes = 0L
                smartSearchDlTotal = 0L
                val ok = embedder.download { done, total -> smartSearchDlBytes = done; smartSearchDlTotal = total }
                smartSearchDownloading = false
                smartSearchDownloadFailed = !ok
                smartSearchModelReady = ok
                if (ok) launch(Dispatchers.IO) { runCatching { embedder.backfill(itemRepository) } }
            }
        }
    }

    // --- App update check / download / install ---
    var availableUpdate by mutableStateOf<AppUpdate?>(null); private set
    var checkingUpdate by mutableStateOf(false); private set
    var checkStatus by mutableStateOf<String?>(null); private set
    var showUpdateSheet by mutableStateOf(false)
    var updateDownloading by mutableStateOf(false); private set
    private var updateDownloadJob: Job? = null
    var updateError by mutableStateOf<String?>(null); private set
    var updateDlBytes by mutableStateOf(0L); private set
    var updateDlTotal by mutableStateOf(0L); private set
    // Set once the APK is fully downloaded but install() had to redirect to the "allow unknown
    // sources" setting — resumed from AppRoot's ON_RESUME instead of forcing a full redownload.
    private var pendingInstallFile: File? = null

    init {
        checkForUpdate(userInitiated = false)
    }

    /** [userInitiated] toggles the busy/status text the manual "Check" button shows — the silent
     * startup check stays quiet unless it actually finds something, matching the old behavior. */
    fun checkForUpdate(userInitiated: Boolean = true) {
        viewModelScope.launch {
            if (userInitiated) { checkingUpdate = true; checkStatus = null }
            val res = runCatching { updateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
            if (userInitiated) checkingUpdate = false
            if (res != null) {
                availableUpdate = res
                showUpdateSheet = true
            } else if (userInitiated) {
                checkStatus = "Latest version installed"
            }
        }
    }

    fun startUpdateDownload() {
        val update = availableUpdate ?: return
        updateDownloading = true
        updateError = null
        updateDlBytes = 0L
        updateDlTotal = 0L
        updateDownloadJob = viewModelScope.launch {
            runCatching {
                val file = updateChecker.download(update) { done, total -> updateDlBytes = done; updateDlTotal = total }
                if (updateChecker.install(file)) {
                    availableUpdate = null
                    showUpdateSheet = false
                } else {
                    pendingInstallFile = file
                }
            }.onFailure {
                updateError = "Download failed: ${it.message}"
            }
            updateDownloading = false
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloading = false
    }

    /** Call from ON_RESUME (in AppRoot, which is always mounted — SettingsScreen might not be). */
    fun retryPendingInstall() {
        val file = pendingInstallFile ?: return
        if (!updateChecker.canInstallPackages()) return
        pendingInstallFile = null
        runCatching { updateChecker.install(file) }
            .onSuccess { launched -> if (launched) { availableUpdate = null; showUpdateSheet = false } }
            .onFailure { updateError = it.message }
    }
}
