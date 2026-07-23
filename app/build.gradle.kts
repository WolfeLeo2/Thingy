import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Set by CI (see .github/workflows/release.yml) so a signed release build doesn't require the
// Android Studio "Generate Signed Bundle/APK" wizard. Local dev keeps using that wizard as before.
val ciKeystorePath: String? = System.getenv("KEYSTORE_PATH")

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.wolfeleo2.thingy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wolfeleo2.thingy"
        minSdk = 29
        targetSdk = 36
        // Overridden by CI via -PVERSION_CODE/-PVERSION_NAME (see release.yml); local builds keep 1/1.0.
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("VERSION_NAME") as String? ?: "1.0"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
        resValue(
            "string", "mapbox_access_token",
            localProperties.getProperty("MAPBOX_ACCESS_TOKEN")
                ?: System.getenv("MAPBOX_ACCESS_TOKEN") ?: "",
        )
        // SerpAPI key for the user-triggered "Find shopping links" pass. Baked into the APK
        // (hobby-scale; same trade-off as the unsigned Cloudinary preset).
        buildConfigField(
            "String", "SERP_API_KEY",
            "\"${localProperties.getProperty("SERP_API_KEY") ?: System.getenv("SERP_API_KEY") ?: ""}\"",
        )
    }

    if (ciKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8: strip dead code (e.g. unused material-icons)
            isShrinkResources = true    // drop unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Locally: signed via the Android Studio "Generate Signed APK/Bundle" wizard
            // (thingy.jks / key0). In CI: signed via the config above, from secrets.
            if (ciKeystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose (BOM-managed; material3 pinned to the alpha in the catalog)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // On-device text embeddings (semantic search) — TFLite runtime via Play Services (no bundled .so)
    implementation(libs.play.services.tflite.java)

    // Settings + coroutines + WorkManager
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material.kolor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.subject.segmentation)
    // Media3 (Video Transcoding & Playback)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Mapbox
    implementation(libs.mapbox.maps)
    implementation(libs.mapbox.compose)

    // Firebase (BOM-managed versions)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // Auth: Google sign-in via Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    debugImplementation(libs.compose.ui.tooling)
}
