package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.wolfeleo2.thingy.data.SyncStatus.reportFailure

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class ItemRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val uid: String? get() = auth.currentUser?.uid
    private val items get() = db.collection("items")
    private val spaceItems get() = db.collection("spaceItems")
    private val spaces get() = db.collection("spaces")

    /**
     * Like [QuerySnapshot.toObjects], but carries each doc's `hasPendingWrites` onto the model so
     * the UI can badge a thingy that hasn't reached the server yet. Only the *listening* reads need
     * this — a one-off `get()` is by definition already synced.
     *
     * Every listener feeding this MUST be registered with [MetadataChanges.INCLUDE]: acknowledging
     * a pending write changes only metadata, not document data, so a default listener delivers no
     * event for it and the badge would stay lit forever on an item that synced fine.
     */
    private fun QuerySnapshot.toItems(): List<Item> = documents.mapNotNull { doc ->
        doc.toObject(Item::class.java)?.copy(pendingSync = doc.metadata.hasPendingWrites())
    }

    fun items(): Flow<List<Item>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = items
            .whereEqualTo("userId", user)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { Log.w("Thingy", "items listen failed", err); trySend(emptyList()); close() }
                else trySend(snap?.toItems().orEmpty())
            }
        awaitClose { reg.remove() }
    }

    /**
     * Fetches items by id regardless of owner — for a shared space's items added by other members.
     *
     * ponytail: a `whereIn` listen is all-or-nothing under security rules — one item the caller can't
     * read rejects the entire query, so a single bad ACL blanks a whole chunk of 30 (that's how the
     * missing suggestSpaces grant showed up as an empty space screen). Kept as-is because the write
     * paths now all grant visibility; if this ever recurs, degrade to per-id listens for the chunk.
     */
    fun itemsByIds(ids: List<String>): Flow<List<Item>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        val chunkFlows = ids.chunked(30).map { chunk ->
            callbackFlow {
                // Once this chunk has produced a value, an error must NOT overwrite it with empty —
                // that's what turned a single rejected listen into a blank space screen; holding the
                // last good value degrades to "stale" rather than "everything vanished". But the first
                // event has to emit something even on failure, or combine() below never produces a
                // value and one bad chunk would hide all the healthy ones.
                var emitted = false
                val reg = items.whereIn(FieldPath.documentId(), chunk).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                    if (err != null) {
                        Log.w("Thingy", "itemsByIds listen failed", err)
                        if (!emitted) { emitted = true; trySend(emptyList()) }
                    } else {
                        emitted = true
                        trySend(snap?.toItems().orEmpty())
                    }
                }
                awaitClose { reg.remove() }
            }
        }
        return combine(chunkFlows) { chunks -> chunks.flatMap { it } }
    }

    fun item(id: String): Flow<Item?> = callbackFlow {
        val reg = items.document(id).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { Log.w("Thingy", "item listen failed", err); trySend(null); close() }
            else trySend(snap?.toObject(Item::class.java)?.copy(pendingSync = snap.metadata.hasPendingWrites()))
        }
        awaitClose { reg.remove() }
    }

    suspend fun createNote(note: String, spaceId: String? = null): String =
        create(Item(type = ItemType.NOTE.wire, note = note, searchText = note), spaceId)

    suspend fun createLink(url: String, spaceId: String? = null): String =
        create(Item(type = ItemType.LINK.wire, url = url, searchText = url), spaceId)

    suspend fun createImage(
        imageUrl: String,
        storagePath: String,
        isSticker: Boolean,
        aspectRatio: Double,
        capturedAt: Long?,
        latitude: Double?,
        longitude: Double?,
        spaceId: String? = null,
    ): String = create(
        Item(
            type = ItemType.IMAGE.wire, imageUrl = imageUrl, storagePath = storagePath,
            sticker = isSticker, aspectRatio = aspectRatio, capturedAt = capturedAt,
            latitude = latitude, longitude = longitude, searchText = "",
        ),
        spaceId,
    )

    suspend fun createVideo(
        imageUrl: String,
        storagePath: String,
        aspectRatio: Double,
        durationMillis: Long,
        capturedAt: Long?,
        latitude: Double?,
        longitude: Double?,
        spaceId: String? = null,
    ): String = create(
        Item(
            type = ItemType.VIDEO.wire, imageUrl = imageUrl, storagePath = storagePath,
            aspectRatio = aspectRatio, durationMillis = durationMillis, capturedAt = capturedAt,
            latitude = latitude, longitude = longitude, searchText = "",
        ),
        spaceId,
    )

    suspend fun createAudio(
        imageUrl: String,
        storagePath: String,
        durationMillis: Long,
        capturedAt: Long?,
        latitude: Double?,
        longitude: Double?,
        spaceId: String? = null,
    ): String = create(
        Item(
            type = ItemType.AUDIO.wire, imageUrl = imageUrl, storagePath = storagePath,
            durationMillis = durationMillis, capturedAt = capturedAt,
            latitude = latitude, longitude = longitude, searchText = "",
        ),
        spaceId,
    )

    /**
     * None of the writes here are awaited, deliberately.
     *
     * A Firestore write Task only completes on *server acknowledgement*, so awaiting it made every
     * save — camera shutter especially — block on a network round-trip (and, in release, on the
     * App Check Play Integrity token behind it) before the UI could move on. The write is applied
     * to the local cache synchronously either way, so the feed's snapshot listener shows the item
     * immediately and the server catches up whenever it can. Offline, the save simply queues.
     *
     * Document ids are generated client-side, which is what makes this possible: [ref] has its id
     * before anything touches the network.
     */
    private suspend fun create(base: Item, spaceId: String?): String {
        val user = requireNotNull(uid) { "Not signed in" }
        val item = base.copy(userId = user, status = ItemStatus.PROCESSING.wire, visibleTo = listOf(user))
        val ref = items.document()
        ref.set(item).reportFailure("Couldn't save — this thingy is only on this device")
        if (spaceId != null) {
            // Grant visibility BEFORE writing the membership row, never after. The row is what every
            // other member's space listener watches: the moment it lands they query this item, and if
            // visibleTo hasn't caught up yet that listen is rejected — permanently, because Firestore
            // does not retry a permission-denied listener. Their space screen then stays blank even
            // though the grant arrives milliseconds later.
            //
            // Dropping the awaits does NOT weaken that ordering: Firestore delivers one client's
            // mutations to the server in the order they were issued, so the arrayUnion still lands
            // ahead of the spaceItems row.
            val memberIds = spaceMemberIds(spaceId)
            if (memberIds.isNotEmpty()) {
                ref.update("visibleTo", FieldValue.arrayUnion(*memberIds.toTypedArray()))
                    .reportFailure("Saved, but others in this space may not see it")
            }
            spaceItems.document("${spaceId}_${ref.id}")
                .set(SpaceItem(userId = user, spaceId = spaceId, itemId = ref.id, status = SpaceItemStatus.SAVED.wire))
                .reportFailure("Saved, but couldn't add it to the space")
        }
        return ref.id
    }

    /**
     * Server-first, deliberately — this is the one read in the save path that must NOT come from
     * cache.
     *
     * memberIds decides who can ever read the item. A cached space doc that predates someone
     * joining yields a short member list, so the item is written readable to fewer people than it
     * should be — and because `itemsByIds` uses a whereIn listen, which security rules reject
     * wholesale when any single matching doc is unreadable, one such item blanks the entire space
     * screen for that member. The damage is silent, permanent until repaired, and costs a co-member
     * their whole view; a few hundred milliseconds here is not worth that.
     */
    private suspend fun spaceMemberIds(spaceId: String): List<String> =
        runCatching { spaces.document(spaceId).get().await().toObject(Space::class.java)?.memberIds }
            .getOrNull().orEmpty()

    /** Write the classifier's result and flip to `ready`. */
    suspend fun finalize(
        id: String,
        title: String,
        description: String,
        tags: List<String>,
        intents: List<Intent>,
        note: String?,
        content: String? = null,
        siteName: String? = null,
        heroImageUrl: String? = null,
        aspectRatio: Double? = null,
        ocrText: String? = null,
        transcript: String? = null,
    ) {
        val searchText = buildSearchText(title, description, tags, note, ocrText, transcript)
        val update = mutableMapOf<String, Any?>(
            "title" to title,
            "description" to description,
            "tags" to tags,
            "intents" to intents.map { mapOf("kind" to it.kind, "label" to it.label, "value" to it.value) },
            "searchText" to searchText,
            "status" to ItemStatus.READY.wire,
        )
        ocrText?.takeIf { it.isNotBlank() }?.let { update["ocrText"] = it }
        transcript?.takeIf { it.isNotBlank() }?.let { update["transcript"] = it }
        content?.let { update["content"] = it }
        siteName?.let { update["siteName"] = it }
        heroImageUrl?.let { update["heroImageUrl"] = it }
        aspectRatio?.let { update["aspectRatio"] = it }
        items.document(id).update(update).await()
    }

    suspend fun markFailed(id: String) {
        items.document(id).update("status", ItemStatus.FAILED.wire).await()
    }

    /** Store the on-device semantic-search vector for an item. */
    suspend fun updateEmbedding(id: String, vector: List<Double>) {
        items.document(id).update("embedding", vector).await()
    }

    /** Mark the "Find links" pass in-flight (or failed) without touching stored products. */
    suspend fun setProductsStatus(id: String, status: ProductsStatus) {
        items.document(id).update("productsStatus", status.wire).await()
    }

    /** Store the SerpAPI shopping results (may be empty) and the terminal status. */
    suspend fun setProducts(id: String, products: List<Product>, status: ProductsStatus) {
        items.document(id).update(
            mapOf(
                "products" to products.map {
                    mapOf(
                        "title" to it.title, "url" to it.url, "price" to it.price,
                        "merchant" to it.merchant, "thumbnailUrl" to it.thumbnailUrl,
                    )
                },
                "productsStatus" to status.wire,
            ),
        ).await()
    }

    /** Ready items, newest first — the candidate pool for space recommendations. */
    suspend fun snapshotItem(id: String): Item? =
        items.document(id).get().await().toObject(Item::class.java)

    /** Every item this user owns, no status filter and no limit — the data export's source. */
    suspend fun snapshotAllItems(): List<Item> {
        val user = uid ?: return emptyList()
        return items.whereEqualTo("userId", user)
            .orderBy("createdAt", Query.Direction.DESCENDING).get().await()
            .toObjects(Item::class.java)
    }

    suspend fun snapshotReadyItems(limit: Long = 100): List<Item> {
        val user = uid ?: return emptyList()
        return items.whereEqualTo("userId", user)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get().await()
            .toObjects(Item::class.java).filter { it.status == ItemStatus.READY.wire }
    }

    /**
     * Patches imageUrl to the Cloudinary CDN URL after a background upload completes.
     * storagePath is intentionally left as the local path (used for file deletion).
     */
    suspend fun updateImageUrl(id: String, cloudinaryUrl: String, newStoragePath: String? = null) {
        val updates = mutableMapOf<String, Any>("imageUrl" to cloudinaryUrl)
        if (newStoragePath != null) updates["storagePath"] = newStoragePath
        items.document(id).update(updates).await()
    }

    /** Points an item at its finished local file — a video's transcode output, replacing the picked URI. */
    suspend fun updateStoragePath(id: String, path: String) {
        items.document(id).update("storagePath", path).await()
    }

    /**
     * Re-queues videos that were marked `failed` only because the classifier raced their transcode
     * (it threw on the content:// URI before the real file existed). One-shot self-heal on sign-in;
     * a no-op once no such items remain.
     */
    suspend fun retryFailedVideos() {
        val user = uid ?: return
        val stale = items.whereEqualTo("userId", user)
            .whereEqualTo("type", ItemType.VIDEO.wire)
            .whereEqualTo("status", ItemStatus.FAILED.wire)
            .get().await().toObjects(Item::class.java)
            .filter { it.storagePath?.startsWith("/") == true && java.io.File(it.storagePath).exists() }
        for (item in stale) {
            runCatching { items.document(item.id).update("status", ItemStatus.PROCESSING.wire).await() }
        }
        if (stale.isNotEmpty()) Log.i("Thingy", "re-queued ${stale.size} failed video(s) for classification")
    }

    /** Permanently delete an item + its space memberships + its local image file. */
    /**
     * Multi-select delete. Sequential on purpose: [delete] also reaps the Cloudinary asset and local
     * files per item, so there's nothing to batch — one failure shouldn't abandon the rest.
     */
    suspend fun deleteAll(ids: Collection<String>) {
        ids.forEach { id -> runCatching { delete(id) }.onFailure { Log.w("Thingy", "delete($id) failed", it) } }
    }

    suspend fun delete(id: String) {
        val user = uid
        // Cascade: remove every spaceItems membership for this item.
        if (user != null) {
            val members = spaceItems.whereEqualTo("userId", user).whereEqualTo("itemId", id).get().await()
            if (!members.isEmpty) {
                val batch = db.batch()
                members.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        // Delete the local image file if one exists, and the Cloudinary asset (if any).
        runCatching {
            val doc = items.document(id).get().await()
            val storagePath = doc.getString("storagePath")
            if (storagePath != null) java.io.File(storagePath).delete()

            // Clean up synced/offline copies in the saved directory
            val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
            val savedDir = java.io.File(context.filesDir, "saved")
            java.io.File(savedDir, "$id.webp").delete()
            java.io.File(savedDir, "$id.mp4").delete()
            java.io.File(savedDir, "$id.media").delete()

            val imageUrl = doc.getString("imageUrl")
            val publicId = imageUrl?.let { cloudinaryPublicIdFrom(it) }
            if (publicId != null) {
                val resourceType = if (doc.getString("type") == ItemType.VIDEO.wire) "video" else "image"
                deleteFromCloudinary(publicId, resourceType)
            }
        }
        items.document(id).delete().await()
    }
}

/**
 * The denormalized blob the substring search filters on.
 *
 * ocrText is folded in (so words inside a screenshot are findable) but deliberately NOT into
 * [embedText] — that blob must stay identical to the classify-time one, and raw OCR noise degrades
 * the vector more than it helps.
 */
internal fun buildSearchText(
    title: String,
    description: String,
    tags: List<String>,
    note: String?,
    ocrText: String?,
    transcript: String? = null,
): String = listOf(
    title, description, tags.joinToString(" "), note.orEmpty(), ocrText.orEmpty(), transcript.orEmpty(),
).filter { it.isNotBlank() }.joinToString(" ").trim()
