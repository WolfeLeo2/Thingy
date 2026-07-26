// Purges accounts that requested deletion (Settings -> Delete account, see AuthRepository.kt
// requestAccountDeletion) more than RETENTION_DAYS ago. Run nightly by
// .github/workflows/purge-deleted-accounts.yml via the Firebase Admin SDK — this is the piece
// that replaces a Cloud Function / Firestore TTL policy, both of which require the Blaze plan
// (see the privacy policy / thingy-map-vendor-decision memory for why this project stays Spark).
//
// Env vars (all provided as GitHub Actions secrets, never committed):
//   FIREBASE_SERVICE_ACCOUNT_JSON  - raw JSON of a Firebase service account key
//   CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET  - same creds baked into the Android app

import { cert, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import crypto from "node:crypto";

const RETENTION_DAYS = 30;
const CLOUDINARY_CLOUD_NAME = "cumjajjx"; // matches CloudinaryDelete.kt
const PUBLIC_ID_REGEX = /\/upload\/(?:v\d+\/)?([^?]+)\.[a-zA-Z0-9]+(?:\?.*)?$/;

function cloudinaryPublicIdFrom(url) {
  const match = url?.match(PUBLIC_ID_REGEX);
  return match?.[1] ?? null;
}

/** Mirrors CloudinaryDelete.kt's signed destroy call, just from Node instead of the app. */
async function deleteFromCloudinary(publicId, resourceType, apiKey, apiSecret) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const toSign = `public_id=${publicId}&timestamp=${timestamp}${apiSecret}`;
  const signature = crypto.createHash("sha1").update(toSign).digest("hex");
  const body = new URLSearchParams({ public_id: publicId, timestamp, api_key: apiKey, signature });
  const res = await fetch(
    `https://api.cloudinary.com/v1_1/${CLOUDINARY_CLOUD_NAME}/${resourceType}/destroy`,
    { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body }
  );
  if (!res.ok) console.warn(`  Cloudinary destroy failed for ${publicId}: ${res.status}`);
}

/** Firestore batches cap at 500 writes; chunk with margin for the odd extra op. */
async function deleteRefsInChunks(db, refs, chunkSize = 450) {
  for (let i = 0; i < refs.length; i += chunkSize) {
    const batch = db.batch();
    refs.slice(i, i + chunkSize).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
}

async function purgeAccount(db, auth, uid, apiKey, apiSecret) {
  console.log(`Purging ${uid}...`);

  // 1. This user's own items: destroy the Cloudinary asset for each, then delete the docs.
  const itemDocs = (await db.collection("items").where("userId", "==", uid).get()).docs;
  for (const doc of itemDocs) {
    const data = doc.data();
    const publicId = data.imageUrl ? cloudinaryPublicIdFrom(data.imageUrl) : null;
    if (publicId && apiKey && apiSecret) {
      const resourceType = data.type === "video" ? "video" : "image";
      await deleteFromCloudinary(publicId, resourceType, apiKey, apiSecret);
    }
  }
  await deleteRefsInChunks(db, itemDocs.map((d) => d.ref));
  console.log(`  deleted ${itemDocs.length} item(s)`);

  // 2. Strip this uid from visibleTo on any item shared into a space they were a member of
  //    (mirrors SpaceRepository.removeMember's client-side cleanup, just for every space at once).
  const sharedItemDocs = (await db.collection("items").where("visibleTo", "array-contains", uid).get()).docs;
  await Promise.all(sharedItemDocs.map((d) => d.ref.update({ visibleTo: [...(d.data().visibleTo ?? [])].filter((id) => id !== uid) })));

  // 3. This user's own spaceItems membership rows.
  const ownSpaceItemDocs = (await db.collection("spaceItems").where("userId", "==", uid).get()).docs;
  await deleteRefsInChunks(db, ownSpaceItemDocs.map((d) => d.ref));

  // 4. Spaces this user OWNS: torn down entirely rather than reassigned — accepted simplification
  //    for a ~5-user hobby app (see tools/account-purge/README.md).
  const ownedSpaceDocs = (await db.collection("spaces").where("userId", "==", uid).get()).docs;
  for (const spaceDoc of ownedSpaceDocs) {
    const spaceId = spaceDoc.id;
    const rows = (await db.collection("spaceItems").where("spaceId", "==", spaceId).get()).docs;
    await deleteRefsInChunks(db, rows.map((d) => d.ref));
    const members = (await db.collection("spaceMembers").where("spaceId", "==", spaceId).get()).docs;
    await deleteRefsInChunks(db, members.map((d) => d.ref));
    const invites = (await db.collection("invites").where("spaceId", "==", spaceId).get()).docs;
    await deleteRefsInChunks(db, invites.map((d) => d.ref));
    await spaceDoc.ref.delete();
  }
  console.log(`  tore down ${ownedSpaceDocs.length} owned space(s)`);

  // 5. Spaces this user is a MEMBER of (not owner): just leave.
  const memberSpaceDocs = (await db.collection("spaces").where("memberIds", "array-contains", uid).get()).docs;
  await Promise.all(
    memberSpaceDocs.map((d) => d.ref.update({ memberIds: (d.data().memberIds ?? []).filter((id) => id !== uid) }))
  );

  // 6. This user's own spaceMembers rows (join records) across every space.
  const spaceMemberDocs = (await db.collection("spaceMembers").where("userId", "==", uid).get()).docs;
  await deleteRefsInChunks(db, spaceMemberDocs.map((d) => d.ref));

  // 7. The deletion marker itself, then the Auth account — last, so a crash above is retryable
  //    tomorrow instead of leaving an unreachable uid with orphaned data.
  await db.collection("accountDeletions").doc(uid).delete();
  await auth.deleteUser(uid).catch((err) => console.warn(`  auth.deleteUser(${uid}) failed: ${err.message}`));

  console.log(`  done.`);
}

async function main() {
  const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!serviceAccountJson) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not set");
  initializeApp({ credential: cert(JSON.parse(serviceAccountJson)) });

  const db = getFirestore();
  const auth = getAuth();
  const apiKey = process.env.CLOUDINARY_API_KEY;
  const apiSecret = process.env.CLOUDINARY_API_SECRET;

  const cutoff = Timestamp.fromMillis(Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000);
  const dueDocs = (await db.collection("accountDeletions").where("requestedAt", "<=", cutoff).get()).docs;

  console.log(`${dueDocs.length} account(s) past the ${RETENTION_DAYS}-day retention window.`);
  for (const doc of dueDocs) {
    await purgeAccount(db, auth, doc.id, apiKey, apiSecret).catch((err) =>
      console.error(`Failed to purge ${doc.id}: ${err.stack ?? err}`)
    );
  }
}

main().then(() => process.exit(0)).catch((err) => {
  console.error(err);
  process.exit(1);
});
