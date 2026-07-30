package com.wolfeleo2.thingy.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Closed unions mirror convex/schema.ts. Stored as their wire string on the Firestore
// data classes (the AI writes these, so an unexpected value must not crash deserialization);
// parse to these enums at call sites via `from()`.
enum class ItemType(val wire: String) {
    IMAGE("image"), LINK("link"), NOTE("note"), VIDEO("video"), AUDIO("audio");
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
enum class SpaceRole(val wire: String) {
    OWNER("owner"), MEMBER("member");
    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: MEMBER }
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
    val ocrText: String? = null,          // text read out of an image by ML Kit; folded into searchText
    val transcript: String? = null,       // spoken words in an audio item, from Gemini; folded into searchText
    val searchText: String = "",
    val embedding: List<Double>? = null,  // on-device semantic-search vector (L2-normalized); null until indexed
    val visibleTo: List<String> = emptyList(),  // denormalized union of memberIds of every shared space this item is in
    @ServerTimestamp val createdAt: Date? = null,
    /**
     * Local-only: this item has writes that haven't reached the server yet. Read off each
     * DocumentSnapshot's metadata when mapping, never stored — hence @get:Exclude, without which
     * Firestore would serialize it back on the next write.
     */
    @get:Exclude val pendingSync: Boolean = false,
)

/**
 * Extension for an item's mirrored copy in `filesDir/saved`, written by OfflineImageSyncer and
 * read back by the card's preview resolver. Shared so the two can't drift — they already had to
 * agree, and adding audio meant adding a third case in both.
 */
internal fun syncedMediaExt(type: String): String = when (type) {
    ItemType.VIDEO.wire -> "mp4"
    ItemType.AUDIO.wire -> "m4a"
    else -> "webp"
}

/** Text fed to the embedder — the meaningful fields, joined. Keep in sync with the classify-time blob. */
fun Item.embedText(): String =
    listOf(title.orEmpty(), description.orEmpty(), tags.joinToString(" "), note.orEmpty(), transcript.orEmpty())
        .filter { it.isNotBlank() }.joinToString(". ").ifBlank { searchText }

/** "0:07" / "2:41" — shared by the recorder's live timer and the card's duration label. */
fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

fun Item.displayTitle(): String =
    title ?: note ?: url?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() } ?: "Untitled"

data class Space(
    @DocumentId val id: String = "",
    val userId: String = "",               // owner
    val name: String = "",
    val description: String? = null,
    val dynamic: Boolean? = null,          // absent = false
    val shelfColor: Long? = null,          // precomputed ambient seed (ARGB) for the shelf-layout board, from shelfColorItemId
    val shelfColorItemId: String? = null,  // the item shelfColor was extracted from — recompute when the newest item changes
    val memberIds: List<String> = emptyList(),   // denormalized uids with access — drives the `spaces()` query and security rules
    val activeInviteCode: String? = null,        // current shareable join code (see SpaceInvite)
    @ServerTimestamp val createdAt: Date? = null,
)

data class SpaceMember(
    @DocumentId val id: String = "",       // "${spaceId}_${userId}"
    val spaceId: String = "",
    val userId: String = "",
    val role: String = SpaceRole.MEMBER.wire,
    val displayName: String? = null,       // denormalized from FirebaseAuth at join time
    val photoUrl: String? = null,
    @ServerTimestamp val joinedAt: Date? = null,
)

/** code -> spaceId lookup for join links/QR; doc id is the code itself. */
data class SpaceInvite(
    @DocumentId val code: String = "",
    val spaceId: String = "",
    @ServerTimestamp val createdAt: Date? = null,
)

/**
 * A note left on a shared space — why something was saved, or a reaction to what someone added.
 *
 * Author name and photo are denormalized at write time, the same way [SpaceMember] snapshots them
 * at join time: a thread has to render whoever wrote each line, and looking that up per comment
 * would be a read per row on every open.
 *
 * Comments outlive their author leaving the space. The owner can delete any of them, so a thread
 * that stops making sense after someone leaves is still tidyable — it just isn't rewritten
 * automatically behind everyone's back.
 */
/**
 * Ceiling on a comment's text, enforced in firestore.rules as `text.size() <= 2000`. The rule is the
 * real trust boundary; the client copy exists so the composer can cap input instead of letting a
 * write be denied after the fact. Keep the two in step.
 */
const val MAX_COMMENT_CHARS = 2000

data class SpaceComment(
    @DocumentId val id: String = "",
    val spaceId: String = "",
    val userId: String = "",
    val text: String = "",
    val authorName: String? = null,
    val authorPhotoUrl: String? = null,
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
val SpaceMember.spaceRole get() = SpaceRole.from(role)
