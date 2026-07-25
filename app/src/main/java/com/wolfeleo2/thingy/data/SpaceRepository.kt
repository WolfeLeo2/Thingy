package com.wolfeleo2.thingy.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// NOTE: spaceItems queries scoped to a *space* (not an item) are spaceId-only — any member of
// the space may read/write its rows. Queries scoped to an *item*'s own steering (suggestSpaces,
// snapshotSavedSpaceIdsForItem) stay userId-scoped since only the owning device's classifier runs them.
class SpaceRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val uid: String? get() = auth.currentUser?.uid
    val currentUserId: String? get() = uid
    private val spaces get() = db.collection("spaces")
    private val spaceItems get() = db.collection("spaceItems")
    private val spaceMembers get() = db.collection("spaceMembers")
    private val invites get() = db.collection("invites")
    private val items get() = db.collection("items")

    /** Grants every member of [spaceId] read access to [itemIds] (denormalized onto Item.visibleTo). */
    private suspend fun grantSpaceVisibility(itemIds: Collection<String>, spaceId: String) {
        if (itemIds.isEmpty()) return
        val memberIds = spaces.document(spaceId).get().await().toObject(Space::class.java)?.memberIds.orEmpty()
        if (memberIds.isEmpty()) return
        val batch = db.batch()
        itemIds.forEach { batch.update(items.document(it), "visibleTo", FieldValue.arrayUnion(*memberIds.toTypedArray())) }
        batch.commit().await()
    }

    // ponytail: recomputeItemVisibility does one read per space-membership + one write per item on every
    // leave/removal (N+1, no batching across items). Fine at hobby-app item counts; if a space ever hits
    // hundreds of items, move this to a Cloud Function triggered off spaceMembers/spaceItems writes.
    //
    // It's also fail-soft by necessity: `spaceItems where itemId==` is rejected outright when the item
    // also sits in a space the caller isn't a member of (rules evaluate the read per result doc), so an
    // exact recompute needs data the client may not read. A Cloud Function is the real fix; until then a
    // failed recompute leaves visibleTo stale (over-permissive) rather than crashing the caller.
    /** Recomputes [itemId]'s visibleTo from scratch: the owner plus every current member of every space it's still in. */
    private suspend fun recomputeItemVisibility(itemId: String) {
        runCatching {
            val ownerUid = items.document(itemId).get().await().getString("userId")
            val spaceIds = spaceItems.whereEqualTo("itemId", itemId).get().await()
                .documents.mapNotNull { it.getString("spaceId") }.distinct()
            val memberIds = spaceIds.flatMap { spaces.document(it).get().await().toObject(Space::class.java)?.memberIds.orEmpty() }
            val visibleTo = (memberIds + listOfNotNull(ownerUid)).distinct()
            items.document(itemId).update("visibleTo", visibleTo).await()
        }.onFailure { Log.w("Thingy", "recomputeItemVisibility($itemId) skipped", it) }
    }

    fun spaces(): Flow<List<Space>> = callbackFlow {
        val user = uid ?: run { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val reg = spaces
            .whereArrayContains("memberIds", user)
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

    /** Every item in [spaceId] regardless of which member added it. */
    fun membershipsForSpace(spaceId: String): Flow<List<SpaceItem>> = callbackFlow {
        val reg = spaceItems
            .whereEqualTo("spaceId", spaceId)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w("Thingy", "space memberships listen failed", err); trySend(emptyList()); close() }
                else trySend(snap?.toObjects(SpaceItem::class.java).orEmpty())
            }
        awaitClose { reg.remove() }
    }

    suspend fun createSpace(name: String, dynamic: Boolean = true): String {
        val user = requireNotNull(uid) { "Not signed in" }
        val id = spaces.add(Space(userId = user, name = name, dynamic = dynamic, memberIds = listOf(user))).await().id
        spaceMembers.document("${id}_$user").set(
            SpaceMember(spaceId = id, userId = user, role = SpaceRole.OWNER.wire, displayName = auth.currentUser?.displayName, photoUrl = auth.currentUser?.photoUrl?.toString())
        ).await()
        return id
    }

    /** People with access to [spaceId] — the avatar stack / member list. */
    fun membersForSpace(spaceId: String): Flow<List<SpaceMember>> = callbackFlow {
        val reg = spaceMembers.whereEqualTo("spaceId", spaceId).addSnapshotListener { snap, err ->
            if (err != null) { Log.w("Thingy", "space members listen failed", err); trySend(emptyList()); close() }
            else trySend(snap?.toObjects(SpaceMember::class.java).orEmpty())
        }
        awaitClose { reg.remove() }
    }

    /** Mints a fresh 6-char code, replacing any previous one (old links/QRs stop working). */
    suspend fun createInviteCode(spaceId: String): String {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        invites.document(code).set(SpaceInvite(spaceId = spaceId)).await()
        spaces.document(spaceId).update("activeInviteCode", code).await()
        return code
    }

    /** Resolves a join code/link and adds the current user as a member. Returns the joined spaceId, or null if invalid. */
    suspend fun joinSpaceByCode(code: String): String? {
        val user = requireNotNull(uid) { "Not signed in" }
        val spaceId = invites.document(code).get().await().getString("spaceId") ?: return null
        spaces.document(spaceId).update("memberIds", FieldValue.arrayUnion(user)).await()
        spaceMembers.document("${spaceId}_$user").set(
            SpaceMember(spaceId = spaceId, userId = user, role = SpaceRole.MEMBER.wire, displayName = auth.currentUser?.displayName, photoUrl = auth.currentUser?.photoUrl?.toString())
        ).await()
        // Grant the new member visibility into every item already in the space.
        val itemIds = spaceItems.whereEqualTo("spaceId", spaceId).get().await().documents.mapNotNull { it.getString("itemId") }.distinct()
        if (itemIds.isNotEmpty()) {
            val batch = db.batch()
            itemIds.forEach { batch.update(items.document(it), "visibleTo", FieldValue.arrayUnion(user)) }
            batch.commit().await()
        }
        return spaceId
    }

    /** Owner removes another member, or a member removes themself (leave). */
    suspend fun removeMember(spaceId: String, userId: String) {
        // Revoke first: once we're out of memberIds the rules stop us reading this space's spaceItems.
        // ponytail: a plain arrayRemove, not a cross-space recompute — if the same item also lives in
        // another shared space this user is still in, they wrongly lose it. Needs the Cloud Function
        // that owns visibleTo (see recomputeItemVisibility); an exact client-side recompute can't read
        // the other members' spaces anyway. The item's owner keeps access via the rules' ownership check.
        val itemIds = spaceItems.whereEqualTo("spaceId", spaceId).get().await()
            .documents.mapNotNull { it.getString("itemId") }.distinct()
        if (itemIds.isNotEmpty()) {
            val batch = db.batch()
            itemIds.forEach { batch.update(items.document(it), "visibleTo", FieldValue.arrayRemove(userId)) }
            runCatching { batch.commit().await() }
                .onFailure { Log.w("Thingy", "revoke visibility on leave failed", it) }
        }
        spaces.document(spaceId).update("memberIds", FieldValue.arrayRemove(userId)).await()
        spaceMembers.document("${spaceId}_$userId").delete().await()
    }

    suspend fun updateSpace(id: String, name: String, dynamic: Boolean) {
        spaces.document(id).update(mapOf("name" to name, "dynamic" to dynamic)).await()
    }

    /** Deletes the space and cascades: memberships, member docs, its invite code, and visibility grants. */
    suspend fun deleteSpace(id: String) {
        val space = snapshotSpace(id)
        val itemDocs = spaceItems.whereEqualTo("spaceId", id).get().await()
        val itemIds = itemDocs.documents.mapNotNull { it.getString("itemId") }.distinct()
        val memberDocs = spaceMembers.whereEqualTo("spaceId", id).get().await()
        // Drop the grants this space handed out before its memberIds become unreadable. Owner keeps
        // access via the rules' ownership check, so stripping them from visibleTo is safe.
        val revoked = space?.memberIds.orEmpty()
        val batch = db.batch()
        itemDocs.documents.forEach { batch.delete(it.reference) }
        memberDocs.documents.forEach { batch.delete(it.reference) }
        if (revoked.isNotEmpty()) {
            itemIds.forEach { batch.update(items.document(it), "visibleTo", FieldValue.arrayRemove(*revoked.toTypedArray())) }
        }
        space?.activeInviteCode?.let { batch.delete(invites.document(it)) }
        batch.commit().await()
        spaces.document(id).delete().await()
        // Re-grant from whatever *other* spaces still hold these items (no-op for solo items).
        itemIds.forEach { recomputeItemVisibility(it) }
    }

    /** Persists the shelf-layout board color, seeded from [itemId]'s image — computed once per newest-item change. */
    suspend fun setShelfColor(spaceId: String, itemId: String, colorArgb: Long) {
        spaces.document(spaceId).update(mapOf("shelfColor" to colorArgb, "shelfColorItemId" to itemId)).await()
    }

    /** One-time backfill for spaces created before sharing existed (no memberIds yet). Safe to re-run. */
    suspend fun migrateLegacySpacesToMemberIds() {
        val user = uid ?: return
        val legacy = spaces.whereEqualTo("userId", user).get().await()
            .documents.filter { it.get("memberIds") == null }
        if (legacy.isEmpty()) return
        val batch = db.batch()
        legacy.forEach { batch.update(it.reference, "memberIds", listOf(user)) }
        batch.commit().await()
        legacy.forEach { doc ->
            spaceMembers.document("${doc.id}_$user").set(
                SpaceMember(spaceId = doc.id, userId = user, role = SpaceRole.OWNER.wire, displayName = auth.currentUser?.displayName, photoUrl = auth.currentUser?.photoUrl?.toString())
            ).await()
        }
    }

    /**
     * Repairs items in shared spaces whose visibleTo never got the co-members added — rows written by
     * the old suggestSpaces (which skipped the grant) blank out every other member's space screen,
     * because one unreadable doc rejects the whole `whereIn` listen rather than just dropping a row.
     *
     * Only touches items *this* user owns, which is all the rules permit and all that's needed: the
     * broken rows on each side were created by that side's own classifier, so both devices running
     * this heal the whole space between them. Idempotent (arrayUnion) — safe to re-run every sign-in.
     */
    suspend fun backfillSharedItemVisibility() {
        val user = uid ?: return
        val shared = spaces.whereArrayContains("memberIds", user).get().await()
            .toObjects(Space::class.java).filter { it.memberIds.size > 1 }
        for (space in shared) {
            val itemIds = spaceItems.whereEqualTo("spaceId", space.id).get().await()
                .documents.mapNotNull { it.getString("itemId") }.distinct()
            if (itemIds.isEmpty()) continue
            // Owner-only: a batch that touches someone else's item would fail the whole commit.
            val mine = items.whereIn(com.google.firebase.firestore.FieldPath.documentId(), itemIds.take(30)).get().await()
                .documents.filter { it.getString("userId") == user }
            val stale = mine.filter { doc ->
                val visible = doc.get("visibleTo") as? List<*> ?: emptyList<Any>()
                !visible.containsAll(space.memberIds)
            }
            if (stale.isEmpty()) continue
            val batch = db.batch()
            stale.forEach { batch.update(it.reference, "visibleTo", FieldValue.arrayUnion(*space.memberIds.toTypedArray())) }
            runCatching { batch.commit().await() }
                .onFailure { Log.w("Thingy", "visibility backfill failed for ${space.id}", it) }
        }
    }

    suspend fun snapshotDynamicSpaces(): List<Space> {
        val user = uid ?: return emptyList()
        return spaces.whereArrayContains("memberIds", user).get().await()
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
        return spaceItems.whereEqualTo("spaceId", spaceId).get().await()
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
            // Must grant, exactly like suggestItemsForSpace does: a suggestion row makes this item part
            // of the space for *every* member's read, and items an co-member can't read take the whole
            // `whereIn` listen down with them (Firestore rejects the query, not the row).
            grantSpaceVisibility(listOf(itemId), sid)
        }
    }

    suspend fun suggestItemsForSpace(spaceId: String, itemIds: List<String>) {
        val user = uid ?: return
        val existing = spaceItems.whereEqualTo("spaceId", spaceId).get().await()
            .toObjects(SpaceItem::class.java).map { it.itemId }.toSet()
        val added = mutableListOf<String>()
        for (id in itemIds) {
            if (id in existing) continue
            spaceItems.document("${spaceId}_$id").set(SpaceItem(userId = user, spaceId = spaceId, itemId = id, status = SpaceItemStatus.SUGGESTED.wire)).await()
            added += id
        }
        grantSpaceVisibility(added, spaceId)
    }

    suspend fun acceptSuggestion(membershipId: String) {
        spaceItems.document(membershipId).update("status", SpaceItemStatus.SAVED.wire).await()
    }

    suspend fun dismissSuggestion(membershipId: String) {
        spaceItems.document(membershipId).update("status", SpaceItemStatus.DISMISSED.wire).await()
    }

    suspend fun dismissAll(spaceId: String) {
        val pending = spaceItems.whereEqualTo("spaceId", spaceId)
            .whereEqualTo("status", SpaceItemStatus.SUGGESTED.wire).get().await()
        val batch = db.batch()
        pending.documents.forEach { batch.update(it.reference, "status", SpaceItemStatus.DISMISSED.wire) }
        batch.commit().await()
    }

    suspend fun acceptAll(spaceId: String): List<String> {
        val pending = spaceItems.whereEqualTo("spaceId", spaceId)
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
        grantSpaceVisibility(listOf(itemId), spaceId)
    }

    /** Multi-select "add to shelf": one batch for the memberships, one visibility grant for the lot. */
    suspend fun addItemsToSpace(itemIds: Collection<String>, spaceId: String) {
        if (itemIds.isEmpty()) return
        val user = requireNotNull(uid) { "Not signed in" }
        val batch = db.batch()
        itemIds.forEach { itemId ->
            batch.set(
                spaceItems.document("${spaceId}_$itemId"),
                SpaceItem(userId = user, spaceId = spaceId, itemId = itemId, status = SpaceItemStatus.SAVED.wire),
            )
        }
        batch.commit().await()
        grantSpaceVisibility(itemIds, spaceId)
    }

    suspend fun removeMembership(membershipId: String) {
        val itemId = spaceItems.document(membershipId).get().await().getString("itemId")
        spaceItems.document(membershipId).delete().await()
        if (itemId != null) recomputeItemVisibility(itemId)
    }

    /** The purpose-steered intents on this item's membership in [spaceId] (empty if none). */
    suspend fun membershipIntents(itemId: String, spaceId: String): List<Intent> {
        return spaceItems.whereEqualTo("spaceId", spaceId)
            .whereEqualTo("itemId", itemId).get().await()
            .toObjects(SpaceItem::class.java).firstOrNull()?.intents ?: emptyList()
    }

    suspend fun setMembershipIntents(itemId: String, spaceId: String, intents: List<Intent>) {
        if (intents.isEmpty()) return
        val q = spaceItems.whereEqualTo("spaceId", spaceId).whereEqualTo("itemId", itemId).get().await()
        val payload = intents.map { mapOf("kind" to it.kind, "label" to it.label, "value" to it.value) }
        q.documents.firstOrNull()?.reference?.update("intents", payload)?.await()
    }
}
