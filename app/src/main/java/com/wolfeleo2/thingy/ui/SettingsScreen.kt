package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.BuildConfig
import com.wolfeleo2.thingy.data.AppUpdate
import com.wolfeleo2.thingy.data.AuthRepository
import com.wolfeleo2.thingy.data.ColorSource
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.SpacesLayout
import com.wolfeleo2.thingy.data.UpdateChecker
import com.wolfeleo2.thingy.ui.theme.dynamicColorSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// Profile page: who you are + how much you've saved, plus the color toggle and sign-out.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    auth: AuthRepository,
    settings: SettingsRepository,
    itemRepository: ItemRepository,
    spaceRepository: SpaceRepository,
    embedder: Embedder,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val user = auth.currentUser
    val colorSource by settings.colorSource.collectAsStateWithLifecycle(ColorSource.DYNAMIC)
    val spacesLayout by settings.spacesLayout.collectAsStateWithLifecycle(SpacesLayout.GRID)
    val smartSearch by settings.smartSearchEnabled.collectAsStateWithLifecycle(false)
    var downloading by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(embedder.isReady()) }
    var dlBytes by remember { mutableLongStateOf(0L) }
    var dlTotal by remember { mutableLongStateOf(0L) }
    val items by remember { itemRepository.items() }.collectAsStateWithLifecycle(emptyList())
    val spaces by remember { spaceRepository.spaces() }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val updateChecker = remember { UpdateChecker(context) }
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var checkStatus by remember { mutableStateOf<String?>(null) }

    // Hoisted here (not owned by UpdateSheet) so the download and its progress survive the sheet
    // being minimized/reopened — the sheet is just a view over this state, not the source of it.
    var showUpdateSheet by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateDownloadJob by remember { mutableStateOf<Job?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var updateDlBytes by remember { mutableLongStateOf(0L) }
    var updateDlTotal by remember { mutableLongStateOf(0L) }
    // Set once the APK is fully downloaded but install() had to redirect to the "allow unknown
    // sources" setting — resumed from ON_RESUME below instead of forcing a full redownload.
    var pendingInstallFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val update = runCatching { updateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
        if (update != null) { availableUpdate = update; showUpdateSheet = true }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val file = pendingInstallFile
                if (file != null && updateChecker.canInstallPackages()) {
                    pendingInstallFile = null
                    runCatching { updateChecker.install(file) }
                        .onSuccess { launched -> if (launched) { availableUpdate = null; showUpdateSheet = false } }
                        .onFailure { updateError = it.message }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startUpdateDownload() {
        val update = availableUpdate ?: return
        updateDownloading = true
        updateError = null
        updateDlBytes = 0L
        updateDlTotal = 0L
        updateDownloadJob = scope.launch {
            runCatching {
                val file = updateChecker.download(update) { done, total -> updateDlBytes = done; updateDlTotal = total }
                if (updateChecker.install(file)) {
                    availableUpdate = null
                    showUpdateSheet = false
                } else {
                    // Redirected to the "allow unknown sources" setting — resume the install from
                    // the already-downloaded file on ON_RESUME.
                    pendingInstallFile = file
                }
            }.onFailure {
                updateError = "Download failed: ${it.message}"
            }
            updateDownloading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    AppBarAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(user?.photoUrl?.toString(), size = 96.dp)
            user?.displayName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.headlineSmall)
            }
            user?.email?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Animated glyph counter — rolls up once per session.
            RollingCounterStat(
                count = items.size,
                label = "Thingies saved",
                modifier = Modifier.padding(top = 12.dp),
            )
            if (spaces.isNotEmpty()) {
                Text(
                    "across ${spaces.size} space${if (spaces.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (dynamicColorSupported) {
                Text("Color", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = ColorSource.entries
                    options.forEachIndexed { i, source ->
                        SegmentedButton(
                            selected = source == colorSource,
                            onClick = { scope.launch { settings.setColorSource(source) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(source.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }

            Text("Spaces layout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val layouts = SpacesLayout.entries
                layouts.forEachIndexed { i, layout ->
                    SegmentedButton(
                        selected = layout == spacesLayout,
                        onClick = { scope.launch { settings.setSpacesLayout(layout) } },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(layout.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            // Smart search: on-device semantic search. Enabling downloads the model once (kept out
            // of the APK to keep it small); once present it indexes existing items and runs offline.
            Text("Search", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smart search", style = MaterialTheme.typography.bodyLarge)
                    fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
                    val status = when {
                        downloading && dlTotal > 0 -> "Downloading model - ${mb(dlBytes)} / ${mb(dlTotal)}"
                        downloading -> "Downloading model…"
                        smartSearch && downloadFailed -> "Download failed. Try Again?"
                        smartSearch && modelReady -> "Semantic search"
                        smartSearch -> "Preparing…"
                        else -> "Search by meaning, not just words. One-time ~few-MB download, then offline."
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = smartSearch,
                    enabled = !downloading,
                    onCheckedChange = { on ->
                        scope.launch {
                            settings.setSmartSearch(on)
                            if (on && !embedder.isReady()) {
                                downloading = true; downloadFailed = false; dlBytes = 0L; dlTotal = 0L
                                val ok = embedder.download { done, total -> dlBytes = done; dlTotal = total }
                                downloading = false; downloadFailed = !ok; modelReady = ok
                                if (ok) launch(Dispatchers.IO) { runCatching { embedder.backfill(itemRepository) } }
                            }
                        }
                    },
                )
            }
            if (downloading) {
                if (dlTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (dlBytes.toFloat() / dlTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // App & Update status card
            Text("App & Updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Thingy v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
                        val statusText = when {
                            updateDownloading && updateDlTotal > 0 -> "Downloading — ${mb(updateDlBytes)} / ${mb(updateDlTotal)}"
                            updateDownloading -> "Downloading update…"
                            availableUpdate != null -> "New update v${availableUpdate?.version} available!"
                            checkingUpdate -> "Checking for updates…"
                            checkStatus != null -> checkStatus!!
                            else -> "App is up to date"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (availableUpdate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (updateDownloading) {
                            Spacer(Modifier.height(6.dp))
                            if (updateDlTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (updateDlBytes.toFloat() / updateDlTotal).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (availableUpdate != null) {
                        Button(
                            onClick = { showUpdateSheet = true },
                            shapes = expressiveButtonShapes()
                        ) {
                            Text(if (updateDownloading) "Downloading…" else "Update")
                        }
                    } else {
                        OutlinedButton(
                            enabled = !checkingUpdate,
                            onClick = {
                                scope.launch {
                                    checkingUpdate = true
                                    checkStatus = null
                                    val res = runCatching { updateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
                                    checkingUpdate = false
                                    if (res != null) {
                                        availableUpdate = res
                                        showUpdateSheet = true
                                    } else {
                                        checkStatus = "Latest version installed"
                                    }
                                }
                            },
                            shapes = expressiveButtonShapes()
                        ) {
                            Text(if (checkingUpdate) "Checking…" else "Check")
                        }
                    }
                }
            }

            OutlinedButton(onClick = onSignOut, shapes = expressiveButtonShapes(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign out") }
        }
    }

    if (showUpdateSheet) {
        availableUpdate?.let { update ->
            UpdateSheet(
                update = update,
                downloading = updateDownloading,
                dlBytes = updateDlBytes,
                dlTotal = updateDlTotal,
                error = updateError,
                onStartDownload = ::startUpdateDownload,
                onCancelDownload = {
                    updateDownloadJob?.cancel()
                    updateDownloading = false
                },
                onDismiss = { showUpdateSheet = false },
            )
        }
    }
}

/**
 * Slot-machine counter: rolls from 0 → [count] with an ease-out rhythm.
 * Runs only ONCE per time this composable enters the composition tree — i.e. once per screen visit.
 * If the user goes back and returns, they see the animation again (new composition = new session).
 * If they rotate the screen, it does NOT replay (remember survives recomposition).
 */
// Session-scoped: the roll plays once per process, not once per screen visit.
private object CounterSession { var rolled = false }

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun RollingCounterStat(count: Int, label: String, modifier: Modifier = Modifier) {
    var displayed by remember { mutableIntStateOf(0) }
    // hasAnimated is remembered across recompositions but resets when the composable leaves the tree.
    var hasAnimated by remember { mutableStateOf(CounterSession.rolled) }

    LaunchedEffect(count) {
        if (hasAnimated || count == 0) {
            displayed = count
            return@LaunchedEffect
        }
        hasAnimated = true
        CounterSession.rolled = true
        val steps = minOf(count, 28)
        for (step in 1..steps) {
            displayed = (count.toLong() * step / steps).toInt()
            // Ease-out: long gaps at the start, short at the end → decelerates onto the real number
            delay(20L + (80L * (steps - step) / steps))
        }
        displayed = count
    }

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = displayed,
            transitionSpec = {
                // Digits slot upward into place
                (slideInVertically(tween(70)) { it } + fadeIn(tween(70)))
                    .togetherWith(slideOutVertically(tween(70)) { -it } + fadeOut(tween(70)))
            },
            label = "thingy_counter",
        ) { value ->
            Text(
                value.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            " $label",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 5.dp), // optical baseline alignment with displaySmall
        )
    }
}

@Composable
private fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = "Avatar", contentScale = ContentScale.Crop,
                modifier = Modifier.clip(CircleShape))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

// Reusable circular avatar for the top bar.
@Composable
fun AvatarButton(url: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(32.dp),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = "Profile", contentScale = ContentScale.Crop,
                modifier = Modifier.clip(CircleShape))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, "Profile", tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
