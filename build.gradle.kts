// Top-level build file. Kotlin is pinned at 2.3.20 here (not in the version catalog);
// the Compose compiler plugin is version-locked to it.
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    // google-services applied in :app once google-services.json exists (Firebase step)
    alias(libs.plugins.google.services) apply false
}
