package com.wolfeleo2.thingy.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Closed unions mirror convex/schema.ts. Stored as their wire string on the Firestore
// data classes (the AI writes these, so an unexpected value must not crash deserialization);
// parse to these enums at call sites via `from()`.
enum class ItemType(val wire: String) {
    IMAGE("image"), LINK("link"), NOTE("note"), VIDEO("video");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}
enum class ItemStatus(val wire: String) {
    PROCESSING("processing"), READY("ready"), FAILED("failed");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}
enum class SpaceItemStatus(val wire: String) {
    SUGGESTED("suggested"), SAVED("saved"), DISMISSED("dismissed");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}
enum class ProductsStatus(val wire: String) {
    SEARCHING("searching"), READY("ready"), FAILED("failed");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}
enum class IntentKind(val wire: String) {
    OPEN_URL("open_url"), COPY("copy"), WEB_SEARCH("web_search"), OPEN_MAPS("open_maps"),
    CALL("call"), EMAIL("email"), MESSAGE("message"), ADD_EVENT("add_event");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}

data class Intent(
    val kind: String = "",
    val label: String = "",
    val value: String = "",
)

data class Product(
    val title: String = "",
    val url: String = "",
    val price: String? = null,
    val merchant: String? = null,
    val thumbnailUrl: String? = null,
)

data class Item(
    @DocumentId val id: String = "",
    val userId: String = "",
    val type: String = "",                 // ItemType.wire
    val status: String = "",               // ItemStatus.wire
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val storagePath: String? = null,       // Cloud Storage path (Amber's storageId)
    val imageUrl: String? = null,          // resolved download URL for display
    val heroImageUrl: String? = null,      // OG image for links
    val aspectRatio: Double? = null,
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sticker: Boolean? = null,   // was isSticker — Firestore drops the 'is' on write; keep names aligned
    val durationMillis: Long? = null,
    val tags: List<String> = emptyList(),
    val content: String? = null,
    val siteName: String? = null,
    val note: String? = null,
    val intents: List<Intent>? = null,
    val products: List<Product>? = null,
    val productsStatus: String? = null,    // ProductsStatus.wire
    val searchText: String = "",
    val embedding: List<Double>? = null,  // on-device semantic-search vector (L2-normalized); null until indexed
    @ServerTimestamp val createdAt: Date? = null,
)

/** Text fed to the embedder — the meaningful fields, joined. Keep in sync with the classify-time blob. */
fun Item.embedText(): String =
    listOf(title.orEmpty(), description.orEmpty(), tags.joinToString(" "), note.orEmpty())
        .filter { it.isNotBlank() }.joinToString(". ").ifBlank { searchText }

fun Item.displayTitle(): String =
    title ?: note ?: url?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() } ?: "Untitled"

data class Space(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val description: String? = null,
    val dynamic: Boolean? = null,          // absent = false
    val shelfColor: Long? = null,          // precomputed ambient seed (ARGB) for the shelf-layout board, from shelfColorItemId
    val shelfColorItemId: String? = null,  // the item shelfColor was extracted from — recompute when the newest item changes
    @ServerTimestamp val createdAt: Date? = null,
)

data class SpaceItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val spaceId: String = "",
    val itemId: String = "",
    val status: String? = null,            // SpaceItemStatus.wire; absent = saved
    val intents: List<Intent>? = null,
)

// Convenience typed accessors
val Item.itemType get() = ItemType.from(type)
val Item.itemStatus get() = ItemStatus.from(status)
val SpaceItem.membershipStatus get() = SpaceItemStatus.from(status) ?: SpaceItemStatus.SAVED
