package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// NOTE: every spaceItems query is scoped `userId ==` first — the security rule only permits
// list queries constrained to the owner's docs. (Equality-only, so no composite indexes needed.)
class SpaceRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val uid: String? get() = auth.currentUser?.uid
    private val spaces get() = db.collection("spaces")
    private val spaceItems get() = db.collection("spaceItems")

    fun spaces(): Flow<List<Space>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = spaces
            .whereEqualTo("userId", user)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w("Thingy", "spaces listen failed", err); trySend(emptyList()); close() }
                else trySend(snap?.toObjects(Space::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    fun space(id: String): Flow<Space?> = callbackFlow {
        val reg = spaces.document(id).addSnapshotListener { snap, err ->
            if (err != null) { Log.w("Thingy", "space listen failed", err); trySend(null); close() }
            else trySend(snap?.toObject(Space::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun memberships(): Flow<List<SpaceItem>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = spaceItems.whereEqualTo("userId", user).addSnapshotListener { snap, err ->
            if (err != null) { Log.w("Thingy", "memberships listen failed", err); trySend(emptyList()); close() }
            else trySend(snap?.toObjects(SpaceItem::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    /** Live memberships for one item across all spaces (drives the add-to-space toggles). */
    fun membershipsForItem(itemId: String): Flow<List<SpaceItem>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = spaceItems.whereEqualTo("userId", user).whereEqualTo("itemId", itemId).addSnapshotListener { snap, err ->
            if (err != null) { Log.w("Thingy", "item memberships listen failed", err); trySend(emptyList()); close() }
            else trySend(snap?.toObjects(SpaceItem::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    fun membershipsForSpace(spaceId: String): Flow<List<SpaceItem>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = spaceItems
            .whereEqualTo("userId", user)
            .whereEqualTo("spaceId", spaceId)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w("Thingy", "space memberships listen failed", err); trySend(emptyList()); close() }
                else trySend(snap?.toObjects(SpaceItem::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    suspend fun createSpace(name: String, dynamic: Boolean = true): String {
        val user = requireNotNull(uid) { "Not signed in" }
        return spaces.add(Space(userId = user, name = name, dynamic = dynamic)).await().id
    }

    suspend fun updateSpace(id: String, name: String, dynamic: Boolean) {
        spaces.document(id).update(mapOf("name" to name, "dynamic" to dynamic)).await()
    }

    suspend fun deleteSpace(id: String) {
        spaces.document(id).delete().await()
    }

    /** Persists the shelf-layout board color, seeded from [itemId]'s image — computed once per newest-item change. */
    suspend fun setShelfColor(spaceId: String, itemId: String, colorArgb: Long) {
        spaces.document(spaceId).update(mapOf("shelfColor" to colorArgb, "shelfColorItemId" to itemId)).await()
    }

    suspend fun snapshotDynamicSpaces(): List<Space> {
        val user = uid ?: return emptyList()
        return spaces.whereEqualTo("userId", user).get().await()
            .toObjects(Space::class.java).filter { it.dynamic == true }
    }

    suspend fun snapshotSpace(id: String): Space? =
        spaces.document(id).get().await().toObject(Space::class.java)

    /** Space ids where this item is a *saved* member (for post-classify steering). */
    suspend fun snapshotSavedSpaceIdsForItem(itemId: String): List<String> {
        val user = uid ?: return emptyList()
        return spaceItems.whereEqualTo("userId", user).whereEqualTo("itemId", itemId).get().await()
            .toObjects(SpaceItem::class.java)
            .filter { it.status == null || it.status == SpaceItemStatus.SAVED.wire }
            .map { it.spaceId }
    }

    suspend fun snapshotMemberItemIds(spaceId: String): Set<String> {
        val user = uid ?: return emptySet()
        return spaceItems.whereEqualTo("userId", user).whereEqualTo("spaceId", spaceId).get().await()
            .toObjects(SpaceItem::class.java)
            .filter { it.status != SpaceItemStatus.DISMISSED.wire }.map { it.itemId }.toSet()
    }

    suspend fun suggestSpaces(itemId: String, spaceIds: List<String>) {
        if (spaceIds.isEmpty()) return
        val user = uid ?: return
        val existing = spaceItems.whereEqualTo("userId", user).whereEqualTo("itemId", itemId).get().await()
            .documents.mapNotNull { it.getString("spaceId") }.toSet()
        for (sid in spaceIds) {
            if (sid in existing) continue
            spaceItems.document("${sid}_$itemId").set(SpaceItem(userId = user, spaceId = sid, itemId = itemId, status = SpaceItemStatus.SUGGESTED.wire)).await()
        }
    }

    suspend fun suggestItemsForSpace(spaceId: String, itemIds: List<String>) {
        val user = uid ?: return
        val existing = spaceItems.whereEqualTo("userId", user).whereEqualTo("spaceId", spaceId).get().await()
            .toObjects(SpaceItem::class.java).map { it.itemId }.toSet()
        for (id in itemIds) {
            if (id in existing) continue
            spaceItems.document("${spaceId}_$id").set(SpaceItem(userId = user, spaceId = spaceId, itemId = id, status = SpaceItemStatus.SUGGESTED.wire)).await()
        }
    }

    suspend fun acceptSuggestion(membershipId: String) {
        spaceItems.document(membershipId).update("status", SpaceItemStatus.SAVED.wire).await()
    }

    suspend fun dismissSuggestion(membershipId: String) {
        spaceItems.document(membershipId).update("status", SpaceItemStatus.DISMISSED.wire).await()
    }

    suspend fun dismissAll(spaceId: String) {
        val user = uid ?: return
        val pending = spaceItems.whereEqualTo("userId", user).whereEqualTo("spaceId", spaceId)
            .whereEqualTo("status", SpaceItemStatus.SUGGESTED.wire).get().await()
        val batch = db.batch()
        pending.documents.forEach { batch.update(it.reference, "status", SpaceItemStatus.DISMISSED.wire) }
        batch.commit().await()
    }

    suspend fun acceptAll(spaceId: String): List<String> {
        val user = uid ?: return emptyList()
        val pending = spaceItems.whereEqualTo("userId", user).whereEqualTo("spaceId", spaceId)
            .whereEqualTo("status", SpaceItemStatus.SUGGESTED.wire).get().await()
        val batch = db.batch()
        pending.documents.forEach { batch.update(it.reference, "status", SpaceItemStatus.SAVED.wire) }
        batch.commit().await()
        return pending.documents.mapNotNull { it.getString("itemId") }
    }

    suspend fun addItemToSpace(itemId: String, spaceId: String) {
        val user = requireNotNull(uid) { "Not signed in" }
        // Deterministic id → idempotent: promotes a suggested row to saved, or creates one, never dupes.
        spaceItems.document("${spaceId}_$itemId")
            .set(SpaceItem(userId = user, spaceId = spaceId, itemId = itemId, status = SpaceItemStatus.SAVED.wire)).await()
    }

    suspend fun removeMembership(membershipId: String) {
        spaceItems.document(membershipId).delete().await()
    }

    /** The purpose-steered intents on this item's membership in [spaceId] (empty if none). */
    suspend fun membershipIntents(itemId: String, spaceId: String): List<Intent> {
        val user = uid ?: return emptyList()
        return spaceItems.whereEqualTo("userId", user).whereEqualTo("spaceId", spaceId)
            .whereEqualTo("itemId", itemId).get().await()
            .toObjects(SpaceItem::class.java).firstOrNull()?.intents ?: emptyList()
    }

    suspend fun setMembershipIntents(itemId: String, spaceId: String, intents: List<Intent>) {
        if (intents.isEmpty()) return
        val user = uid ?: return
        val q = spaceItems.whereEqualTo("userId", user)
            .whereEqualTo("spaceId", spaceId).whereEqualTo("itemId", itemId).get().await()
        val payload = intents.map { mapOf("kind" to it.kind, "label" to it.label, "value" to it.value) }
        q.documents.firstOrNull()?.reference?.update("intents", payload)?.await()
    }
}
