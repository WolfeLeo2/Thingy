package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

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

    fun items(): Flow<List<Item>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = items
            .whereEqualTo("userId", user)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w("Thingy", "items listen failed", err); trySend(emptyList()); close() }
                else trySend(snap?.toObjects(Item::class.java).orEmpty())
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
                val reg = items.whereIn(FieldPath.documentId(), chunk).addSnapshotListener { snap, err ->
                    if (err != null) { Log.w("Thingy", "itemsByIds listen failed", err); trySend(emptyList()) }
                    else trySend(snap?.toObjects(Item::class.java).orEmpty())
                }
                awaitClose { reg.remove() }
            }
        }
        return combine(chunkFlows) { chunks -> chunks.flatMap { it } }
    }

    fun item(id: String): Flow<Item?> = callbackFlow {
        val reg = items.document(id).addSnapshotListener { snap, err ->
            if (err != null) { Log.w("Thingy", "item listen failed", err); trySend(null); close() }
            else trySend(snap?.toObject(Item::class.java))
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

    private suspend fun create(base: Item, spaceId: String?): String {
        val user = requireNotNull(uid) { "Not signed in" }
        val item = base.copy(userId = user, status = ItemStatus.PROCESSING.wire, visibleTo = listOf(user))
        val ref = items.add(item).await()
        if (spaceId != null) {
            spaceItems.document("${spaceId}_${ref.id}")
                .set(SpaceItem(userId = user, spaceId = spaceId, itemId = ref.id, status = SpaceItemStatus.SAVED.wire)).await()
            val memberIds = spaces.document(spaceId).get().await().toObject(Space::class.java)?.memberIds.orEmpty()
            if (memberIds.isNotEmpty()) items.document(ref.id).update("visibleTo", FieldValue.arrayUnion(*memberIds.toTypedArray())).await()
        }
        return ref.id
    }

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
    ) {
        val searchText = listOf(title, description, tags.joinToString(" "), note.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ").trim()
        val update = mutableMapOf<String, Any?>(
            "title" to title,
            "description" to description,
            "tags" to tags,
            "intents" to intents.map { mapOf("kind" to it.kind, "label" to it.label, "value" to it.value) },
            "searchText" to searchText,
            "status" to ItemStatus.READY.wire,
        )
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
