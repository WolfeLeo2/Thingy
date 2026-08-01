package com.wolfeleo2.thingy.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.wolfeleo2.thingy.data.AudioIngestor
import com.wolfeleo2.thingy.data.AuthRepository
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.CloudinaryMigration
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.OfflineSyncWorker
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceRepository
import androidx.core.content.pm.ShortcutManagerCompat
import com.wolfeleo2.thingy.data.SpaceShortcuts
import com.wolfeleo2.thingy.data.SyncStatus
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.nav.Camera
import com.wolfeleo2.thingy.nav.Canvas
import com.wolfeleo2.thingy.nav.Home
import com.wolfeleo2.thingy.nav.ItemDetail
import com.wolfeleo2.thingy.nav.Login
import com.wolfeleo2.thingy.nav.Map
import com.wolfeleo2.thingy.nav.NewSpace
import com.wolfeleo2.thingy.nav.Onboarding
import com.wolfeleo2.thingy.nav.PrivacyPolicy
import com.wolfeleo2.thingy.nav.Settings
import com.wolfeleo2.thingy.nav.SpaceDetail
import com.wolfeleo2.thingy.ui.auth.LoginScreen
import com.wolfeleo2.thingy.ui.canvas.CanvasScreen
import com.wolfeleo2.thingy.ui.camera.CameraScreen
import com.wolfeleo2.thingy.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Nav3 gate: root key derived from state (auth + onboarding); pushes (detail, space, settings)
 * happen only while signed-in + onboarded, so the gate never resets them.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun AppRoot(
    auth: AuthRepository,
    settings: SettingsRepository,
    serverClientId: String?,
    sharedText: String? = null,
    onSharedConsumed: () -> Unit = {},
    sharedImages: List<android.net.Uri> = emptyList(),
    onImagesConsumed: () -> Unit = {},
    openItemId: String? = null,
    onOpenItemConsumed: () -> Unit = {},
    joinCode: String? = null,
    onJoinCodeConsumed: () -> Unit = {},
    /** Space chosen in the share sheet (Direct Share target) — shared content saves into it. */
    sharedSpaceId: String? = null,
    openSpaceId: String? = null,
    onOpenSpaceConsumed: () -> Unit = {},
    /** Quick-capture button on the home-screen widget. */
    openCamera: Boolean = false,
    onOpenCameraConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val itemRepository = remember { ItemRepository() }
    val spaceRepository = remember { SpaceRepository() }
    val embedder = remember { Embedder(appContext) }
    val classifier = remember { Classifier(appContext, itemRepository, spaceRepository, embedder) }
    val ingestor = remember { ImageIngestor(appContext, itemRepository) }
    val videoIngestor = remember { VideoIngestor(appContext, itemRepository) }
    val audioIngestor = remember { AudioIngestor(appContext, itemRepository) }
    val cloudinaryMigration = remember { CloudinaryMigration() }

    val user by auth.authState.collectAsStateWithLifecycle(auth.currentUser)

    // Hoisted here (not inside MainShell) so MapScreen — a sibling nav destination — shares the
    // same warm StateFlows instead of opening a second, independent Firestore listener.
    // The uid must be the ViewModel's *store* key, not a Compose key(): viewModel() resolves from the
    // Activity's ViewModelStore by class name, which key() has no effect on, so wrapping it returned
    // the SAME instance after an account switch. That mattered because signing out makes Firestore
    // re-evaluate every live listener with no auth, each handler close()s its callbackFlow, and a
    // closed flow never re-emits — so the next account inherited three dead listeners and saw
    // PERMISSION_DENIED on items/spaces/memberships until the process was restarted.
    val library: LibraryViewModel = viewModel(key = "library:${user?.uid}") {
        LibraryViewModel(itemRepository, spaceRepository)
    }
    // Owns update-check/download + smart-search-download state in viewModelScope (survives Nav3
    // popping Settings off the back stack — a rememberCoroutineScope() inside SettingsScreen does
    // not, which is what silently killed in-flight downloads on navigating away).
    val settingsViewModel: SettingsViewModel = viewModel(key = "settings:${user?.uid}") {
        SettingsViewModel(appContext, settings, itemRepository, embedder)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settingsViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsViewModel.retryPendingInstall()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val onboardedFlow = remember(settings) { settings.onboardingComplete.map<Boolean, Boolean?> { it } }
    val onboarded by onboardedFlow.collectAsStateWithLifecycle(null)
    val smartSearch by settings.smartSearchEnabled.collectAsStateWithLifecycle(false)

    // When smart search is on and the model is present, index any not-yet-embedded items.
    LaunchedEffect(user?.uid, smartSearch) {
        if (user != null && smartSearch && embedder.isReady()) {
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { embedder.backfill(itemRepository) } }
        }
    }

    // Housekeeping. Each job is rate-limited by claimMaintenanceRun so it doesn't run on *every*
    // cold start — they all hit Firestore/Cloudinary and were contending with the first listener
    // for network. The one-off backfills get a long interval; the syncer stays comparatively eager.
    LaunchedEffect(user?.uid) {
        if (user != null) {
            // Migrate legacy items (local / Firebase Storage) to Cloudinary in the background.
            launch(kotlinx.coroutines.Dispatchers.IO) {
                if (settings.claimMaintenanceRun("cloudinary_migration", 1.days)) {
                    runCatching { cloudinaryMigration.run() }
                }
            }
            // Backfill spaces created before sharing existed with memberIds. A genuine one-time
            // migration, so a long interval is fine.
            launch(kotlinx.coroutines.Dispatchers.IO) {
                if (settings.claimMaintenanceRun("space_backfills", 7.days)) {
                    runCatching { spaceRepository.migrateLegacySpacesToMemberIds() }
                }
            }
            // Repair shared-space items whose visibleTo is missing co-members. UNGATED on purpose:
            // this is not housekeeping, it's the safety net for a failure that blanks a co-member's
            // entire space screen and never self-corrects. Rate-limiting it once cost a week of
            // being broken — claimMaintenanceRun stamps on claim, so one failed run disables the
            // repair for the whole interval. Runs every launch, as it did before.
            // ponytail: N document gets per shared space per launch. Fine at this app's scale; if a
            // space ever holds hundreds of items, trigger it from the itemsByIds listen failure
            // instead of on a timer.
            launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { spaceRepository.backfillSharedItemVisibility() }
            }
            // Downloading missing images to filesDir is WorkManager's job — it needs a network
            // constraint and to keep running with the app closed. See OfflineSyncWorker.
            OfflineSyncWorker.schedule(appContext)
            // Self-heal videos that were failed by the classifier/transcode race (fixed 2026-07-27).
            launch(kotlinx.coroutines.Dispatchers.IO) {
                if (settings.claimMaintenanceRun("retry_failed_videos", 6.hours)) {
                    runCatching { itemRepository.retryFailedVideos() }
                }
            }
            runCatching { classifier.run() } // collects the feed; cancels on sign-out
        } else {
            // Nothing to sync for a signed-out device, and the worker would otherwise keep waking
            // every 6h to find no user.
            OfflineSyncWorker.cancel(appContext)
        }
    }

    // Checked once per uid (not a Firestore listener — this is a one-off gate, not something that
    // needs to react live). Deliberately does NOT gate the UI: on release builds this get() waits
    // on an App Check Play Integrity token (~3s cold, forever offline), and blocking rootKey on it
    // meant every cold start showed a blank Surface for that long. A user mid-deletion sees Home
    // for a frame instead — the screen below swaps in as soon as the check lands.
    var pendingDeletionRequestedAt by remember { mutableStateOf<java.util.Date?>(null) }
    LaunchedEffect(user?.uid) {
        val uid = user?.uid
        pendingDeletionRequestedAt = if (uid != null) runCatching { auth.pendingDeletionRequestedAt(uid) }.getOrNull() else null
    }

    val rootKey: NavKey? = when {
        onboarded == null -> null
        user == null -> Login
        onboarded == false -> Onboarding
        else -> Home
    }
    if (rootKey == null) {
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    val deletionRequestedAt = pendingDeletionRequestedAt
    if (user != null && deletionRequestedAt != null) {
        AccountPendingDeletionScreen(
            requestedAt = deletionRequestedAt,
            onCancelDeletion = {
                scope.launch {
                    runCatching { auth.cancelAccountDeletion() }
                    pendingDeletionRequestedAt = null
                }
            },
            onSignOut = { auth.signOut() },
        )
        return
    }

    val backStack = remember { mutableStateListOf(rootKey) }
    var lastPopTime by remember { mutableStateOf(0L) }
    val onBack: () -> Unit = {
        val now = System.currentTimeMillis()
        if (backStack.size > 1 && now - lastPopTime > 300L) {
            backStack.removeLastOrNull()
            lastPopTime = now
        }
    }

    LaunchedEffect(rootKey) {
        if (backStack.lastOrNull() != rootKey) {
            backStack.clear()
            backStack.add(rootKey)
        }
    }

    // Deep link from notification: open ItemDetail directly
    LaunchedEffect(openItemId, user?.uid, onboarded) {
        val targetId = openItemId
        if (targetId != null && user != null && onboarded == true) {
            backStack.add(ItemDetail(itemIds = listOf(targetId), startIndex = 0, disableSharedTransition = true))
            onOpenItemConsumed()
        }
    }

    // Deep link from a space invite (link/QR): join, then open the space.
    LaunchedEffect(joinCode, user?.uid, onboarded) {
        val code = joinCode
        if (code != null && user != null && onboarded == true) {
            runCatching { spaceRepository.joinSpaceByCode(code) }.getOrNull()?.let { spaceId ->
                backStack.add(SpaceDetail(spaceId))
            }
            onJoinCodeConsumed()
        }
    }

    // Quick-capture from the home-screen widget.
    LaunchedEffect(openCamera, user?.uid, onboarded) {
        if (openCamera && user != null && onboarded == true) {
            backStack.add(Camera())
            onOpenCameraConsumed()
        }
    }

    // Space shortcut tapped from the launcher's long-press menu.
    LaunchedEffect(openSpaceId, user?.uid, onboarded) {
        val spaceId = openSpaceId
        if (spaceId != null && user != null && onboarded == true) {
            backStack.add(SpaceDetail(spaceId))
            onOpenSpaceConsumed()
        }
    }

    // Share-in: turn shared text into a link (if a URL) or a note, once the app is usable.
    // sharedSpaceId is set when the user picked a per-space Direct Share target; null = general feed.
    LaunchedEffect(sharedText, user?.uid, onboarded) {
        val text = sharedText
        if (text != null && user != null && onboarded == true) {
            runCatching {
                if (android.util.Patterns.WEB_URL.matcher(text).matches()) itemRepository.createLink(text, sharedSpaceId)
                else itemRepository.createNote(text, sharedSpaceId)
            }
            onSharedConsumed()
        }
    }

    LaunchedEffect(sharedImages, user?.uid, onboarded) {
        if (sharedImages.isNotEmpty() && user != null && onboarded == true) {
            val cr = appContext.contentResolver
            runCatching {
                sharedImages.forEach { uri ->
                    if (cr.getType(uri)?.startsWith("video/") == true) {
                        videoIngestor.ingestUri(uri, spaceId = sharedSpaceId)
                    } else {
                        ingestor.ingestUri(uri, asSticker = false, spaceId = sharedSpaceId)
                    }
                }
            }
            onImagesConsumed()
        }
    }

    // A share that DID pick a space is a usage signal — the system ranks Direct Share targets partly
    // on this, and unreported shortcuts drift down and stop being offered.
    LaunchedEffect(sharedSpaceId) {
        sharedSpaceId?.let { runCatching { ShortcutManagerCompat.reportShortcutUsed(appContext, it) } }
    }

    // Keep the share sheet's per-space targets in step with the user's spaces.
    val spacesForShortcuts by library.spaces.collectAsStateWithLifecycle()
    // Keyed on what a shortcut actually shows, not list identity — otherwise every Firestore
    // snapshot republishes and hits the launcher's setDynamicShortcuts rate limit for nothing.
    val shortcutKey = spacesForShortcuts?.take(4)?.joinToString { "${it.id}:${it.name}" }
    LaunchedEffect(user?.uid, shortcutKey) {
        if (user == null) SpaceShortcuts.clear(appContext)
        else spacesForShortcuts?.let { SpaceShortcuts.publish(appContext, it) }
    }

    val snackbar = remember { SnackbarHostState() }
    val notify = remember { { message: String -> scope.launch { snackbar.showSnackbar(message) }; Unit } }
    // Writes that nobody awaits (item creation, background uploads) report here — see SyncStatus.
    LaunchedEffect(Unit) { SyncStatus.failures.collect { snackbar.showSnackbar(it) } }
    CompositionLocalProvider(LocalNotify provides notify) {
      Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = onBack,
            sharedTransitionScope = this,
            // Sync the content fade (default is 700ms) with the shared-element spring (~350ms) so the
            // hero morph and the cross-fade finish together — both forward AND on back-button pop.
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            popTransitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            entryProvider = entryProvider {
                entry<Login> {
                    LoginScreen(
                        auth = auth,
                        serverClientId = serverClientId,
                        onOpenPolicy = { backStack.add(PrivacyPolicy) },
                    )
                }
                entry<PrivacyPolicy> { PolicyScreen(onBack = onBack) }
                entry<Onboarding> {
                    OnboardingScreen(onStart = { scope.launch { settings.setOnboardingComplete() } })
                }
                entry<Home> {
                    MainShell(
                        userId = user?.uid,
                        library = library,
                        itemRepository = itemRepository,
                        spaceRepository = spaceRepository,
                        classifier = classifier,
                        settings = settings,
                        embedder = embedder,
                        ingestor = ingestor,
                        videoIngestor = videoIngestor,
                        audioIngestor = audioIngestor,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        avatarUrl = user?.photoUrl?.toString(),
                        onOpenItem = { ids, index -> backStack.add(ItemDetail(ids, index)) },
                        onOpenSpace = { backStack.add(SpaceDetail(it)) },
                        onOpenSettings = { backStack.add(Settings) },
                        onOpenMap = { backStack.add(Map) },
                        onOpenCanvas = { backStack.add(Canvas) },
                        onOpenSpaceSettings = { backStack.add(NewSpace(it)) },
                        onOpenCamera = { backStack.add(Camera()) },
                    )
                }
                entry<Settings> {
                    SettingsScreen(
                        auth = auth,
                        settings = settings,
                        itemRepository = itemRepository,
                        spaceRepository = spaceRepository,
                        settingsViewModel = settingsViewModel,
                        onSignOut = { auth.signOut() },
                        onBack = onBack,
                    )
                }
                entry<Map> {
                    MapScreen(
                        library = library,
                        onOpenItem = { ids, index, disableShared -> backStack.add(ItemDetail(ids, index, disableSharedTransition = disableShared)) },
                        onBack = onBack,
                    )
                }
                entry<Canvas> {
                    CanvasScreen(
                        library = library,
                        // Unlike Map, the canvas's cards *are* the framed image, so they morph into
                        // the detail hero properly — no disableSharedTransition needed. Home isn't
                        // composed while Canvas is on top, so the shared keys can't collide.
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        onOpenItem = { ids, index -> backStack.add(ItemDetail(ids, index)) },
                        onBack = onBack,
                    )
                }
                entry<ItemDetail> { key ->
                    ItemDetailScreen(
                        itemRepository = itemRepository,
                        spaceRepository = spaceRepository,
                        classifier = classifier,
                        settings = settings,
                        itemIds = key.itemIds,
                        startIndex = key.startIndex,
                        spaceId = key.spaceId,
                        sharedTransitionScope = if (key.disableSharedTransition) null else this@SharedTransitionLayout,
                        animatedVisibilityScope = if (key.disableSharedTransition) null else LocalNavAnimatedContentScope.current,
                        onOpenItem = { ids, index -> backStack.add(ItemDetail(ids, index, disableSharedTransition = true)) },
                        onBack = onBack,
                    )
                }
                entry<SpaceDetail> { key ->
                    SpaceDetailScreen(
                        spaceId = key.spaceId,
                        userId = user?.uid,
                        itemRepository = itemRepository,
                        spaceRepository = spaceRepository,
                        classifier = classifier,
                        ingestor = ingestor,
                        videoIngestor = videoIngestor,
                        audioIngestor = audioIngestor,
                        settings = settings,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        onOpenItem = { ids, index -> backStack.add(ItemDetail(ids, index, key.spaceId)) },
                        onEdit = { backStack.add(NewSpace(key.spaceId)) },
                        onOpenCamera = { backStack.add(Camera(key.spaceId)) },
                        onBack = onBack,
                    )
                }
                entry<Camera> { key ->
                    CameraScreen(ingestor = ingestor, spaceId = key.spaceId, onDone = onBack)
                }
                entry<NewSpace> { key ->
                    NewSpaceScreen(
                        spaceId = key.spaceId,
                        spaceRepository = spaceRepository,
                        itemRepository = itemRepository,
                        classifier = classifier,
                        onDone = onBack,
                        scope = scope,
                    )
                }
            },
        )
        }
        // 88dp clears the floating toolbar on the tabbed shell; harmless on the pushed screens.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
      }
    }
}
