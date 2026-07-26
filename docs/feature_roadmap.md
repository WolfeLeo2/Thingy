# Thingy Feature Roadmap

The first roadmap (ask-your-stuff, resurfacing, smart space suggestions, collage share, ambient
theming) is **fully shipped** — see `MEMORY.md` / the shipped code for how each one actually landed,
which differs from the original research in places (embeddings run on-device via TFLite, not
Firestore Vector Search; there are no Cloud Functions anywhere — Spark plan).

This is the next five, ordered by value ÷ effort. Constraints unchanged: **Firebase Spark, no
billing, no Cloud Functions**, everything client-side or a GitHub Actions cron.

---

## 1. OCR on saved images — *built, unreleased*

**Goal:** Make the text *inside* screenshots searchable. Screenshots are the most-saved item type
and right now every word in them is invisible to search — only Gemini's title/description/tags are.

### Approach
- **ML Kit Text Recognition v2**, Play-Services variant (`play-services-mlkit-text-recognition`) —
  same distribution model as the subject-segmentation dep already shipped, so the model lives in
  Play Services and adds ~nothing to the APK.
- Runs inside `Classifier.classify()`'s existing `IMAGE` branch, on the bitmap it **already
  decoded** for Gemini. No second decode, no new pass over the feed, one place all image saves
  (camera, gallery, share-in) already route through.
- Result stored as `Item.ocrText` and folded into the denormalized `searchText` that
  `ItemRepository.finalize()` writes, so the existing substring search picks it up for free.

### Deliberate limits
- Latin script only.
- Capped at 2 000 chars — Firestore docs stay small and a wall of OCR noise doesn't drown the
  real fields.
- Not fed to the embedder: `embedText()` and the classify-time blob must stay identical, and raw
  OCR noise degrades a 384-dim mean-pooled vector. Substring search is where OCR pays off.
- **No backfill.** Existing images stay un-OCR'd; only new saves get it. A backfill would have to
  re-download every image from Cloudinary. Add one modelled on `Embedder.backfill()` if it turns
  out to matter.

### Testing
Save a screenshot with distinctive text, wait for classification, search a word that appears only
in the image — it should return that item.

---

## 2. Per-space share targets — *built, unreleased*

**Goal:** Share a photo from any app straight into a specific space. Today share-in always lands in
the general feed, and filing it costs a further open → detail → add-to-space.

### Approach
- `ShortcutManagerCompat.setDynamicShortcuts()` publishes the user's most recent spaces as
  **long-lived, share-target shortcuts**; `res/xml/shortcuts.xml` declares the `<share-target>`
  with the same mime types as the existing `ACTION_SEND` intent-filters.
- Android delivers the chosen target's shortcut id as `Intent.EXTRA_SHORTCUT_ID` — the id *is* the
  space id, so `MainActivity` just threads it through as `sharedSpaceId` alongside the existing
  `sharedText` / `sharedImages`.
- Ingest already takes a `spaceId` on every path (`ingestUri`, `createLink`, `createNote`), so the
  save side needs no new plumbing.

### Deliberate limits
- Top 4 spaces by recency, no ranking model. Android's own usage ranking sorts them in the sheet.
- No per-space icons (would need to render a cover thumbnail into an adaptive icon on a background
  thread and republish on every cover change) — one shared shape mark instead.

### Testing
Share an image from Photos; the Android share sheet should offer "Thingy › <space>" rows, and
picking one saves the image with that space's membership row already written.

---

## 3. Glance home-screen widget — *built, unreleased*

**Goal:** Resurfacing that lives on the home screen instead of a notification you swipe away.

### Approach
- `androidx.glance:glance-appwidget` **1.2.0-rc01** — Compose-flavoured widget API, no XML
  RemoteViews. Coexists fine with Compose 1.5-alpha on `compileSdk 37`.
- One resizable widget, not two: today's resurfaced item (title + thumbnail, tap opens it) with a
  **quick-capture** button in the header that deep-links straight into `CameraScreen`.
- The widget reads **only** `SettingsRepository.widgetCard` — a flat DataStore snapshot
  (`itemId`/`title`/`thumbPath`) that `ResurfaceWorker` writes when it makes today's pick, then
  repaints via `ThingyWidget.refresh()`. A widget renders outside any signed-in foreground session,
  so it must never open a Firestore listener; stale-but-present beats empty-and-correct.
- Thumbnails are local files only (`Item.previewModel` → `File`) — no network path from a widget —
  and are decoded size-capped at render time, since RemoteViews bitmaps cross a Binder transaction.

### Fixed along the way
`pickResurfaceTarget` (extracted from `ResurfaceWorker` to make it testable) treated a null
`createdAt` as epoch, i.e. maximally old. `createdAt` is a `@ServerTimestamp`, so null means the
write hasn't been acked yet — a just-saved item could surface as a "memory". Now excluded.

---

## 4. Export my data

**Goal:** Complete the privacy story. Self-service deletion shipped; portability is the other half,
and it's the one thing a data-hoarding app owes its users.

### Approach
- A `.zip` written straight to `Downloads` via `MediaStore` (no permission needed on API 29+):
  `items.json` (every field of every owned item, spaces, memberships) plus the media files, taken
  from `filesDir` where present and Cloudinary otherwise.
- Run in a `WorkManager` job with a progress notification — this is minutes of work over a
  connection, not something to hang a screen on.
- Entry point: Settings → Account, directly above "Delete account".

### Deliberate limits
Media that only ever existed on another device and was never Cloudinary-synced can't be exported;
say so in the UI rather than silently producing a partial archive.

---

## 5. Near-duplicate detection at ingest

**Goal:** Stop the same receipt/screenshot accumulating five times.

### Approach
- Cosine over the embeddings **already computed** for smart search — `Embedder.cosine`, the same
  call "More like this" uses. Zero new infrastructure.
- At classify-finalize, compare the new item's vector against recent ready items; above a
  threshold (tune from `Embedder.MIN_SCORE`, which is calibrated for *relatedness* — duplicates
  need something much stricter, ~0.95) surface a dismissible "you already saved something like
  this" affordance on the card.

### Deliberate limits
- Only works when smart search is enabled — that's what populates `embedding`. Silently inert
  otherwise, which is the right failure mode.
- Suggest, never auto-delete. Two photos of the same receipt may both be wanted.
