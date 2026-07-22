package com.wolfeleo2.thingy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URL
import java.util.Collections

/**
 * Client-side AI (Firebase AI Logic / Gemini Developer API free tier). Ports Amber's
 * convex/ai.ts to run on-device — no Cloud Functions, so it works on the free Spark plan.
 * Prompts, schemas, and intent-sanitizing rules are kept identical to Amber.
 */
class Classifier(
    private val context: Context,
    private val items: ItemRepository = ItemRepository(),
    private val spaces: SpaceRepository = SpaceRepository(),
    private val embedder: Embedder? = null,
) {
    private fun genModel(schema: Schema, system: String? = null): GenerativeModel =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
            systemInstruction = system?.let { content { text(it) } },
        )

    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    // Free Gemini tier rate-limits concurrent requests — cap in-flight classifications so a batch
    // of uploads doesn't blast N calls at once and get some 429'd.
    private val gate = Semaphore(MAX_CONCURRENT)

    /** Collects the feed and classifies any `processing` item exactly once. Runs while signed in. */
    suspend fun run() = coroutineScope {
        items.items().distinctUntilChanged().collect { list ->
            list.asSequence()
                .filter { it.status == ItemStatus.PROCESSING.wire && it.id.isNotEmpty() && inFlight.add(it.id) }
                .forEach { item ->
                    launch(Dispatchers.IO) {
                        try {
                            gate.withPermit { classifyWithRetry(item) }
                        } finally {
                            inFlight.remove(item.id)
                        }
                    }
                }
        }
    }

    /**
     * Classify with a few retries + backoff so a transient failure (free-tier 429, or a network
     * call interrupted when the OS backgrounds/freezes the app) doesn't permanently mark the item
     * failed. Cancellation is rethrown untouched — the item stays `processing` and is retried on the
     * next launch. Only a persistent error finally flips it to `failed`.
     */
    private suspend fun classifyWithRetry(item: Item) {
        var attempt = 0
        while (true) {
            try {
                classify(item)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    Log.w("Thingy", "classify failed for ${item.id} after $attempt attempts", e)
                    runCatching { items.markFailed(item.id) }
                    return
                }
                Log.i("Thingy", "classify retry $attempt for ${item.id}: ${e.message}")
                delay(2000L * attempt) // linear backoff — enough to clear a free-tier rate spike
            }
        }
    }

    private suspend fun classify(item: Item) {
        val dynamic = spaces.snapshotDynamicSpaces()
        val spacesBlock = spacesPromptBlock(dynamic)

        var page: Page? = null
        val model = genModel(ANALYSIS_SCHEMA, SYSTEM_PROMPT)
        val response = when (item.type) {
            ItemType.LINK.wire -> {
                val url = item.url ?: throw IllegalStateException("Link item has no url")
                page = fetchPage(url)
                model.generateContent(linkPrompt(url, page!!, spacesBlock))
            }
            ItemType.NOTE.wire -> {
                val note = item.note ?: throw IllegalStateException("Note item has no text")
                model.generateContent(notePrompt(note, spacesBlock))
            }
            ItemType.IMAGE.wire -> {
                val bmp = loadBitmap(item.imageUrl, item.storagePath)
                    ?: throw IllegalStateException("Image not readable")
                model.generateContent(content { text(mediaPrompt(isVideo = false, spacesBlock)); image(bmp) })
            }
            ItemType.VIDEO.wire -> {
                val path = item.storagePath?.takeIf { it.startsWith("/") } ?: throw IllegalStateException("Video has no local path")
                val file = java.io.File(path)
                if (!file.exists()) throw IllegalStateException("Video file missing")
                val bytes = file.readBytes()
                model.generateContent(content { text(mediaPrompt(isVideo = true, spacesBlock)); inlineData(bytes, "video/mp4") })
            }
            else -> throw IllegalStateException("Unsupported type: ${item.type}")
        }

        val json = JSONObject(response.text ?: "{}")
        val title = json.optString("title").trim()
        val description = json.optString("description").trim()
        val tags = json.optJSONArray("tags").toStringList().map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val spaceNames = json.optJSONArray("spaceNames").toStringList()
        val intents = sanitizeIntents(parseIntents(json.optJSONArray("intents")))

        val idByName = dynamic.associateBy({ it.name.trim().lowercase() }, { it.id })
        val spaceIds = spaceNames.mapNotNull { idByName[it.trim().lowercase()] }

        items.finalize(
            id = item.id,
            title = title,
            description = description,
            tags = tags,
            intents = intents,
            note = item.note,
            content = if (item.type == ItemType.LINK.wire) page?.content else null,
            siteName = if (item.type == ItemType.LINK.wire) page?.siteName else null,
            heroImageUrl = if (item.type == ItemType.LINK.wire) page?.heroImageUrl else null,
            aspectRatio = if (item.type == ItemType.LINK.wire) page?.aspectRatio else null,
        )
        spaces.suggestSpaces(item.id, spaceIds)
        // Steer any spaces the item was already filed into while it was processing.
        spaces.snapshotSavedSpaceIdsForItem(item.id).forEach { steerItemForSpace(item.id, it) }

        // Index for on-device semantic search (no-op if the user hasn't enabled it / model absent).
        embedder?.let { e ->
            val blob = listOf(title, description, tags.joinToString(" "), item.note.orEmpty())
                .filter { it.isNotBlank() }.joinToString(". ")
            e.embed(blob)?.let { runCatching { items.updateEmbedding(item.id, it) } }
        }
    }

    /**
     * Recommend existing ready items for a space off its title/description. Runs on space
     * create and when Dynamic turns on. Writes `suggested` rows only. Ports ai.ts recommendForSpace.
     */
    suspend fun recommendForSpace(spaceId: String) {
        try {
            val space = spaces.snapshotSpace(spaceId) ?: return
            val memberIds = spaces.snapshotMemberItemIds(spaceId)
            val candidates = items.snapshotReadyItems(100).filter { it.id !in memberIds }
            if (candidates.isEmpty()) return

            val itemLines = candidates.mapIndexed { i, it ->
                val parts = listOf(
                    it.title ?: "(untitled)",
                    it.description.orEmpty(),
                    if (it.tags.isNotEmpty()) "tags: ${it.tags.joinToString(", ")}" else "",
                ).filter { p -> p.isNotEmpty() }
                "${i + 1}. ${parts.joinToString(" — ")}"
            }.joinToString("\n")

            val prompt = listOf(
                "You are helping organize a save-it-for-later app. The user just created a space (a themed collection) and Amber recommends a few existing saves for it — the user decides which to keep.",
                "Space name: \"${space.name}\"${space.description?.let { "\nSpace description: $it" } ?: ""}",
                "Below is a numbered list of the user's saved items. Return the numbers of a handful of items that CLEARLY belong in this space — quality over quantity, high-confidence picks only, at most 8. If nothing clearly fits, return an empty array.",
                itemLines,
            ).joinToString("\n\n")

            val json = JSONObject(genModel(RECOMMEND_SCHEMA).generateContent(prompt).text ?: "{}")
            val nums = json.optJSONArray("itemNumbers")
            val picked = mutableListOf<String>()
            if (nums != null) {
                for (i in 0 until nums.length()) {
                    val n = nums.optInt(i, -1)
                    if (n in 1..candidates.size) picked.add(candidates[n - 1].id)
                    if (picked.size >= 8) break
                }
            }
            if (picked.isNotEmpty()) spaces.suggestItemsForSpace(spaceId, picked)
        } catch (e: Exception) {
            Log.w("Thingy", "recommendForSpace failed for $spaceId", e)
        }
    }

    /**
     * Purpose-steering: when an item enters a space, its title steers up to 3 space-scoped
     * intents written to that membership row. Ports ai.ts steerItemForSpace.
     */
    suspend fun steerItemForSpace(itemId: String, spaceId: String) {
        try {
            val item = items.snapshotItem(itemId) ?: return
            val space = spaces.snapshotSpace(spaceId) ?: return
            if (item.status != ItemStatus.READY.wire) return

            val prompt = listOf(
                "You are helping a save-it-for-later app. The user filed a saved item into their space \"${space.name}\" — treat that title as a statement of purpose and propose up to 3 actions ('intents') that serve it for this specific item.",
                listOf(
                    "Item title: ${item.title ?: "(untitled)"}",
                    item.description?.let { "Description: $it" } ?: "",
                    if (item.tags.isNotEmpty()) "Tags: ${item.tags.joinToString(", ")}" else "",
                    item.url?.let { "URL: $it" } ?: "",
                ).filter { it.isNotEmpty() }.joinToString("\n"),
                INTENTS_PROMPT_BLOCK,
                "Steering by space purpose:",
                "- Shopping/wishlist space: identify the product and include an open_url intent to a Google Shopping search, https://www.google.com/search?tbm=shop&q=PRODUCT+QUERY, labeled like 'Shop this'.",
                "- Travel space: prefer open_maps for places and open_url for official/booking pages you can actually see.",
                "- Recipes/cooking space: a web_search for the dish or an open_url to the recipe.",
                "Only propose intents that genuinely serve this space's purpose — the item's general actions already exist elsewhere. An empty list is fine.",
            ).joinToString("\n\n")

            val json = JSONObject(genModel(STEER_SCHEMA).generateContent(prompt).text ?: "{}")
            val intents = sanitizeIntents(parseIntents(json.optJSONArray("intents"))).take(3)
            if (intents.isNotEmpty()) spaces.setMembershipIntents(itemId, spaceId, intents)
        } catch (e: Exception) {
            Log.w("Thingy", "steerItemForSpace failed for $itemId in $spaceId", e)
        }
    }

    // --- on-device page fetch/extract (Mozilla Readability via Readability4J) ---

    private data class Page(
        val title: String?, val description: String?, val heroImageUrl: String?,
        val siteName: String?, val content: String?, val aspectRatio: Double?,
    )

    private suspend fun fetchPage(url: String): Page = withContext(Dispatchers.IO) {
        val resp = Jsoup.connect(url).userAgent(UA).timeout(15000).followRedirects(true).execute()
        val finalUrl = resp.url().toString()
        val html = resp.body()
        val doc = Jsoup.parse(html, finalUrl)
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (doc.selectFirst("meta[property=$k]") ?: doc.selectFirst("meta[name=$k]"))
                ?.attr("content")?.takeIf { it.isNotBlank() }
        }
        val title = meta("og:title") ?: doc.title().ifBlank { null }
        val description = meta("og:description", "description")
        val heroImageUrl = meta("og:image", "og:image:url", "twitter:image")?.let {
            runCatching { URL(URL(finalUrl), it).toString() }.getOrNull()
        }
        val siteName = meta("og:site_name")
            ?: runCatching { URI(finalUrl).host?.removePrefix("www.") }.getOrNull()
        val ogW = meta("og:image:width")?.toDoubleOrNull()
        val ogH = meta("og:image:height")?.toDoubleOrNull()
        val aspectRatio = if (ogW != null && ogH != null && ogW > 0 && ogH > 0) ogW / ogH else null

        val readable = runCatching { Readability4J(finalUrl, html).parse().textContent?.trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val content = (readable ?: run {
            val scope = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            scope?.select("script,style,nav,header,footer,aside,noscript,form,iframe")?.remove()
            val paras = scope?.select("p")?.eachText()?.filter { it.isNotBlank() }.orEmpty()
            if (paras.isNotEmpty()) paras.joinToString("\n\n") else scope?.text().orEmpty()
        }).take(MAX_STORED_CONTENT_CHARS).ifBlank { null }

        Page(title, description, heroImageUrl, siteName, content, aspectRatio)
    }

    private fun linkPrompt(url: String, p: Page, spacesBlock: String) = listOf(
        "You are helping organize a save-it-for-later app. Analyze this saved web page and produce a title, a 1-2 sentence description, 4-8 lowercase tags (one or two words each), and matching space names.",
        spacesBlock,
        "URL: $url",
        p.title?.let { "Page title: $it" } ?: "",
        p.siteName?.let { "Site: $it" } ?: "",
        p.description?.let { "Meta description: $it" } ?: "",
        p.content?.let { "Page content:\n${it.take(6000)}" } ?: "No page content could be extracted.",
        INTENTS_PROMPT_BLOCK,
    ).filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun notePrompt(note: String, spacesBlock: String) = listOf(
        "You are helping organize a save-it-for-later app. Analyze this saved note and produce a short evocative title, a 1-2 sentence description, 4-8 lowercase tags (one or two words each), and matching space names.",
        spacesBlock,
        "Note:\n${note.take(MAX_CONTENT_CHARS)}",
        INTENTS_PROMPT_BLOCK,
    ).joinToString("\n\n")

    private fun mediaPrompt(isVideo: Boolean, spacesBlock: String) = listOf(
        "You are helping organize a save-it-for-later app. Analyze this saved ${if (isVideo) "video" else "image"} and produce a short evocative title, a 1-2 sentence description of what it shows, 4-8 lowercase tags (one or two words each), and matching space names.",
        "CRITICAL: If the media depicts a specific pop culture moment, a scene from a movie or TV series, a famous online place/game, a recognizable person, or a specific historical/sports event (e.g. 'Messi playing France in the 2022 World Cup', or 'The black hole scene from Interstellar'), explicitly identify and name the movie, series, people, and context in both the title and the description.",
        spacesBlock,
        INTENTS_PROMPT_BLOCK,
    ).joinToString("\n\n")

    /**
     * Loads the image bitmap for AI classification.
     * - If imageUrl is a Cloudinary HTTPS URL (after upload): downloads with w_1024,q_auto
     *   transform so we don't send a full-res image to Gemini. Falls back to local file on
     *   network failure.
     * - If imageUrl is a local absolute path (before upload, or offline): decodes directly.
     * storagePath is always the local path and is used as the fallback.
     */
    private suspend fun loadBitmap(imageUrl: String?, storagePath: String?): Bitmap? = withContext(Dispatchers.IO) {
        val primary = imageUrl ?: storagePath ?: return@withContext null
        if (primary.startsWith("http")) {
            // Cloudinary: insert resize + quality transform into the upload URL
            val fetchUrl = primary.replace("/image/upload/", "/image/upload/w_1024,q_auto/")
            val remote = runCatching {
                val bytes = URL(fetchUrl).readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            // Fallback to local file if the network fetch fails
            remote ?: storagePath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        } else {
            val path = Uri.parse(primary).path ?: return@withContext null
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }

    private fun spacesPromptBlock(spaces: List<Space>): String {
        if (spaces.isEmpty()) return "The user has no spaces yet, so spaceNames must be an empty array."
        val lines = spaces.joinToString("\n") { "- \"${it.name}\"${it.description?.let { d -> ": $d" } ?: ""}" }
        return "The user organizes items into spaces. Candidate spaces:\n$lines\n\nIn spaceNames, include only the exact names of spaces this item CLEARLY belongs to. Only include confident matches. If none clearly match, return an empty array."
    }

    private fun parseIntents(arr: JSONArray?): List<Intent> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Intent(it.optString("kind"), it.optString("label"), it.optString("value")) }
        }
    }

    private fun sanitizeIntents(raw: List<Intent>): List<Intent> {
        val seen = mutableSetOf<String>()
        return raw.asSequence()
            .filter { IntentKind.from(it.kind) != null }
            .map { Intent(it.kind, it.label.trim().take(40), it.value.trim()) }
            .filter { it.label.isNotEmpty() && it.value.isNotEmpty() }
            .filter {
                when (it.kind) {
                    IntentKind.OPEN_URL.wire -> Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it.value)
                    IntentKind.EMAIL.wire -> it.value.contains("@")
                    IntentKind.CALL.wire, IntentKind.MESSAGE.wire -> it.value.any { c -> c.isDigit() }
                    else -> true
                }
            }
            .filter { seen.add("${it.kind}|${it.value.lowercase()}") }
            .take(5)
            .toList()
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private companion object {
        // User-selected; Amber's model. GA/available on the free tier.
        // Vertex AI backend — bills via Blaze (no $25 prepayment) + $300 credit eligible.
        // Vertex model id (not the Developer-API "gemini-3.1-flash-lite"). Bump if you want newer.
        const val MODEL = "gemini-3.1-flash-lite"
        const val MAX_CONCURRENT = 2   // free-tier-friendly concurrency cap for classification
        const val MAX_ATTEMPTS = 3     // retries before a transient failure becomes `failed`
        const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val MAX_CONTENT_CHARS = 8000
        const val MAX_STORED_CONTENT_CHARS = 100000

        const val SYSTEM_PROMPT =
            "You are the classifier for Thingy, a save-it-for-later app. Titles must be short and " +
            "punchy — like a label on a folder, not a headline. Aim for 2-4 words, never a full " +
            "sentence, and never end with a period."

        val INTENTS_PROMPT_BLOCK = listOf(
            "Also propose up to 5 useful actions ('intents') the user could take on this item. Only include ones that clearly apply — an empty list is fine, and do not pad. Each intent has a kind, a short label (1-3 words, no trailing punctuation), and a value (the payload). Available kinds:",
            "- open_url: open a link, or deep-link into a native app (a social post, video, profile, product page). value must be a full https:// URL. For a social post in a screenshot, if you can clearly read the @handle, link to that profile (e.g. https://x.com/HANDLE) — NEVER invent a post/status id you cannot actually see. If the saved item already has a URL pointing at a specific post, use that exact URL.",
            "- copy: copy a short, specific string to the clipboard (an address, code, wallet/handle, quoted line). Put the exact text in value.",
            "- web_search: search the web. value is the query.",
            "- open_maps: open a place in maps. value is a place name or address.",
            "- call: call a phone number. value is the phone number.",
            "- message: text a phone number. value is the phone number.",
            "- email: email someone. value is the email address.",
            "- add_event: add a calendar event. value is the event title.",
            "Give each a concrete label like 'Open in X', 'Copy address', 'Call', or 'Add to calendar'.",
        ).joinToString("\n")

        private val INTENT_SCHEMA = Schema.obj(
            mapOf(
                "kind" to Schema.enumeration(
                    listOf("open_url", "copy", "web_search", "open_maps", "call", "email", "message", "add_event"),
                ),
                "label" to Schema.string(),
                "value" to Schema.string(),
            ),
        )

        val ANALYSIS_SCHEMA: Schema = Schema.obj(
            mapOf(
                "title" to Schema.string(),
                "description" to Schema.string(),
                "tags" to Schema.array(Schema.string()),
                "spaceNames" to Schema.array(Schema.string()),
                "intents" to Schema.array(INTENT_SCHEMA),
            ),
        )

        val RECOMMEND_SCHEMA: Schema = Schema.obj(mapOf("itemNumbers" to Schema.array(Schema.integer())))
        val STEER_SCHEMA: Schema = Schema.obj(mapOf("intents" to Schema.array(INTENT_SCHEMA)))
    }
}
