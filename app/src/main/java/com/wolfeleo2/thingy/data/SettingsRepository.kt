package com.wolfeleo2.thingy.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ColorSource { DYNAMIC, AMBER }
enum class SpacesLayout { GRID, SHELF }

private val Context.dataStore by preferencesDataStore(name = "settings")
private val COLOR_SOURCE = stringPreferencesKey("color_source")
private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
private val TIDY_ALBUM = stringPreferencesKey("tidy_album")
private val SMART_SEARCH = booleanPreferencesKey("smart_search")
private val SPACES_LAYOUT = stringPreferencesKey("spaces_layout")
private val DISMISSED_SUGGESTIONS = stringSetPreferencesKey("dismissed_space_suggestions")
private val RESURFACED_ITEM_ID = stringPreferencesKey("resurfacing_item_id")
private val DISMISSED_RESURFACE_ID = stringPreferencesKey("dismissed_resurface_id")
private val SNOOZED_ITEMS = stringPreferencesKey("snoozed_items_map")

class SettingsRepository(private val context: Context) {
    val colorSource: Flow<ColorSource> = context.dataStore.data.map { prefs ->
        prefs[COLOR_SOURCE]?.let { runCatching { ColorSource.valueOf(it) }.getOrNull() }
            ?: ColorSource.DYNAMIC
    }

    val spacesLayout: Flow<SpacesLayout> = context.dataStore.data.map { prefs ->
        prefs[SPACES_LAYOUT]?.let { runCatching { SpacesLayout.valueOf(it) }.getOrNull() }
            ?: SpacesLayout.GRID
    }

    suspend fun setSpacesLayout(layout: SpacesLayout) {
        context.dataStore.edit { it[SPACES_LAYOUT] = layout.name }
    }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    val tidyAlbumId: Flow<String?> = context.dataStore.data.map { it[TIDY_ALBUM] }

    /** Semantic (on-device embedding) search. Off by default — enabling it downloads the model. */
    val smartSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[SMART_SEARCH] ?: false }

    suspend fun setSmartSearch(enabled: Boolean) {
        context.dataStore.edit { it[SMART_SEARCH] = enabled }
    }

    suspend fun setColorSource(source: ColorSource) {
        context.dataStore.edit { it[COLOR_SOURCE] = source.name }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    suspend fun setTidyAlbum(id: String?) {
        context.dataStore.edit { prefs -> if (id == null) prefs.remove(TIDY_ALBUM) else prefs[TIDY_ALBUM] = id }
    }

    /** Tags the user dismissed a "make a space?" suggestion for — local only, keeps it from reappearing. */
    val dismissedSuggestions: Flow<Set<String>> =
        context.dataStore.data.map { it[DISMISSED_SUGGESTIONS] ?: emptySet() }

    suspend fun dismissSuggestion(tag: String) {
        context.dataStore.edit { prefs ->
            prefs[DISMISSED_SUGGESTIONS] = (prefs[DISMISSED_SUGGESTIONS] ?: emptySet()) + tag.lowercase()
        }
    }

    /**
     * Resurfaced item ID for today's "On this day / Blast from the past" card.
     * Stored as "id|yyyy-MM-dd" so dismissing today's pick doesn't also suppress the same
     * item if the daily random fallback ever re-picks it on a later day.
     */
    val resurfacedItemId: Flow<String?> = context.dataStore.data.map { prefs ->
        val current = prefs[RESURFACED_ITEM_ID]
        val dismissed = prefs[DISMISSED_RESURFACE_ID]
        if (current != null && current != dismissed) current.substringBefore("|") else null
    }

    suspend fun setResurfacedItemId(id: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        context.dataStore.edit { it[RESURFACED_ITEM_ID] = "$id|$today" }
    }

    suspend fun dismissResurfacing() {
        context.dataStore.edit { prefs -> prefs[RESURFACED_ITEM_ID]?.let { prefs[DISMISSED_RESURFACE_ID] = it } }
    }

    /** Map of itemId -> targetEpochMs for snoozed items. */
    val snoozedItems: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        val raw = prefs[SNOOZED_ITEMS].orEmpty()
        if (raw.isEmpty()) emptyMap()
        else {
            raw.split(";").mapNotNull { entry ->
                val parts = entry.split("=")
                if (parts.size == 2) {
                    val id = parts[0]
                    val time = parts[1].toLongOrNull()
                    if (id.isNotEmpty() && time != null && time > System.currentTimeMillis()) {
                        id to time
                    } else null
                } else null
            }.toMap()
        }
    }

    suspend fun snoozeItem(itemId: String, epochMs: Long) {
        context.dataStore.edit { prefs ->
            val currentMap = (prefs[SNOOZED_ITEMS].orEmpty()).split(";").filter { it.isNotEmpty() }.toMutableList()
            currentMap.removeAll { it.startsWith("$itemId=") }
            currentMap.add("$itemId=$epochMs")
            prefs[SNOOZED_ITEMS] = currentMap.joinToString(";")
        }
    }

    suspend fun unsnoozeItem(itemId: String) {
        context.dataStore.edit { prefs ->
            val currentMap = (prefs[SNOOZED_ITEMS].orEmpty()).split(";").filter { it.isNotEmpty() }.toMutableList()
            currentMap.removeAll { it.startsWith("$itemId=") }
            prefs[SNOOZED_ITEMS] = currentMap.joinToString(";")
        }
    }
}
