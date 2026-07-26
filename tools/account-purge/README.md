# Account purge

Runs nightly via `.github/workflows/purge-deleted-accounts.yml` to permanently delete any account
that requested deletion (Settings → Delete account) 30+ days ago. Not called from the app —
Firestore TTL and Cloud Functions both require the Blaze plan, which this project deliberately
stays off (see the privacy policy). This script + a GitHub Actions cron is the free-tier
replacement: it's just an authenticated Admin SDK script, unrelated to the Blaze gate.

## One-time setup (do this before the workflow can run)

1. **Firebase service account key:** Firebase console → Project settings → Service accounts →
   "Generate new private key". Copy the full JSON contents.
2. Add these as **repo secrets** (Settings → Secrets and variables → Actions):
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — the whole JSON from step 1, pasted as-is.
   - `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` — same values as `local.properties`.
3. That's it — the workflow is already scheduled once those secrets exist.

To run it manually (e.g. to test): Actions tab → "Purge deleted accounts" → "Run workflow".

## Accepted simplifications

- **Owned spaces are torn down entirely, not reassigned.** If the deleted user owned a shared
  space, the space, its items' membership rows, its members, and its invite codes are all deleted
  outright rather than transferring ownership to another member. Fine at ~5-user hobby scale;
  would need real thought at any bigger scale.
- **Invite codes are only cleaned up for spaces the deleted user owned** (torn down along with the
  space). Invite codes for spaces they were just a member of are untouched — the space still exists
  for its remaining members, so those codes are still valid and not orphaned.
- **A crash partway through one user's purge is retryable the next night** — the deletion marker
  and the Auth account are removed last, specifically so a partial failure leaves the account still
  "pending" instead of stuck half-purged with no way to find it again.
