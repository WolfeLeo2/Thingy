package com.wolfeleo2.thingy

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.google.firebase.appcheck.FirebaseAppCheck

class ThingyApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Install App Check before any Firebase service is used.
        // The debug provider is substituted automatically in debug builds via
        // the debugImplementation dependency; release builds use Play Integrity.
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            appCheckProviderFactory() // debug/ → DebugAppCheckProviderFactory, release/ → PlayIntegrity
        )
    }

    // Coil3 doesn't wire a network fetcher by default; register OkHttp explicitly.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
