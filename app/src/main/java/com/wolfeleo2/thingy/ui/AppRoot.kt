package com.wolfeleo2.thingy.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.wolfeleo2.thingy.data.VideoIngestor
import com.wolfeleo2.thingy.nav.Home
import com.wolfeleo2.thingy.nav.Map
import com.wolfeleo2.thingy.nav.Camera
import com.wolfeleo2.thingy.nav.ItemDetail
import com.wolfeleo2.thingy.nav.Login
import com.wolfeleo2.thingy.nav.NewSpace
import com.wolfeleo2.thingy.nav.Onboarding
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
            // Download any missing images to filesDir for true offline access.
            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { offlineSyncer.run() } }
            runCatching { classifier.run() } // collects the feed; cancels on sign-out
        }
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

    // Share-in: turn shared text into a link (if a URL) or a note, once the app is usable.
    LaunchedEffect(sharedText, user?.uid, onboarded) {
        val text = sharedText
        if (text != null && user != null && onboarded == true) {
            runCatching {
                if (android.util.Patterns.WEB_URL.matcher(text).matches()) itemRepository.createLink(text)
                else itemRepository.createNote(text)
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
                        videoIngestor.ingestUri(uri)
                    } else {
                        ingestor.ingestUri(uri, asSticker = false)
                    }
                }
            }
            onImagesConsumed()
        }
    }

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
                entry<Login> { LoginScreen(auth = auth, serverClientId = serverClientId) }
                entry<Onboarding> {
                    OnboardingScreen(onStart = { scope.launch { settings.setOnboardingComplete() } })
                }
                entry<Home> {
                    MainShell(
                        userId = user?.uid,
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
                        embedder = embedder,
                        onSignOut = { auth.signOut() },
                        onBack = onBack,
                    )
                }
                entry<Map> {
                    MapScreen(
                        itemRepository = itemRepository,
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
}
