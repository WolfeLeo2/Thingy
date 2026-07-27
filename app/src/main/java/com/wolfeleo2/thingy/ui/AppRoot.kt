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
import androidx.compose.runtime.key
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
import com.wolfeleo2.thingy.data.AuthRepository
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.CloudinaryMigration
import com.wolfeleo2.thingy.data.Embedder
import com.wolfeleo2.thingy.data.ImageIngestor
import com.wolfeleo2.thingy.data.ItemRepository
import com.wolfeleo2.thingy.data.OfflineImageSyncer
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.SpaceRepository
import com.wolfeleo2.thingy.data.SpaceShortcuts
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.nav.Camera
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
import com.wolfeleo2.thingy.ui.camera.CameraScreen
import com.wolfeleo2.thingy.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    val cloudinaryMigration = remember { CloudinaryMigration() }
    val offlineSyncer = remember { OfflineImageSyncer(appContext) }

    val user by auth.authState.collectAsStateWithLifecycle(auth.currentUser)

    // Hoisted here (not inside MainShell) so MapScreen — a sibling nav destination — shares the
    // same warm StateFlows instead of opening a second, independent Firestore listener.
    val library: LibraryViewModel = key(user?.uid) {
        viewModel { LibraryViewModel(itemRepository, spaceRepository) }
    }
    // Owns update-check/download + smart-search-download state in viewModelScope (survives Nav3
    // popping Settings off the back stack — a rememberCoroutineScope() inside SettingsScreen does
    // not, which is what silently killed in-flight downloads on navigating away).
    val settingsViewModel: SettingsViewModel = key(user?.uid) {
        viewModel { SettingsViewModel(appContext, settings, itemRepository, embedder) }
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

    LaunchedEffect(user?.uid) {
        if (user != null) {
            // Migrate legacy items (local / Firebase Storage) to Cloudinary in the background.
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { cloudinaryMigration.run() } }
            // Backfill spaces created before sharing existed with memberIds, then repair any shared-space
            // items whose visibleTo is missing co-members (one such item blanks the whole space screen).
            launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { spaceRepository.migrateLegacySpacesToMemberIds() }
                runCatching { spaceRepository.backfillSharedItemVisibility() }
            }
            // Download any missing images to filesDir for true offline access.
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { offlineSyncer.run() } }
            runCatching { classifier.run() } // collects the feed; cancels on sign-out
        }
    }

    // Checked once per uid (not a Firestore listener — this is a one-off gate, not something that
    // needs to react live). null uid = not signed in; the deletion check itself resolving is
    // tracked separately so the gate below can't flash Home before it's known.
    var pendingDeletionCheckedFor by remember { mutableStateOf<String?>(null) }
    var pendingDeletionRequestedAt by remember { mutableStateOf<java.util.Date?>(null) }
    LaunchedEffect(user?.uid) {
        val uid = user?.uid
        pendingDeletionRequestedAt = if (uid != null) runCatching { auth.pendingDeletionRequestedAt(uid) }.getOrNull() else null
        pendingDeletionCheckedFor = uid
    }
    val pendingDeletionKnown = user == null || pendingDeletionCheckedFor == user?.uid

    val rootKey: NavKey? = when {
        onboarded == null || !pendingDeletionKnown -> null
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
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        avatarUrl = user?.photoUrl?.toString(),
                        onOpenItem = { ids, index -> backStack.add(ItemDetail(ids, index)) },
                        onOpenSpace = { backStack.add(SpaceDetail(it)) },
                        onOpenSettings = { backStack.add(Settings) },
                        onOpenMap = { backStack.add(Map) },
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
                        itemRepository = itemRepository,
                        spaceRepository = spaceRepository,
                        classifier = classifier,
                        ingestor = ingestor,
                        videoIngestor = videoIngestor,
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
