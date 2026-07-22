# Thingy — Product Requirements

A Material 3 Expressive, Android-native port of Amber. This document is the **what** and the
**how it feels**. For the exact current behavior of any feature, read the mapped Amber source
(`AMBER_REFERENCE.md`).

---

## 1. Product in one paragraph

Thingy is a calm "save-it-for-later" hub. You throw in **links, images, and notes**; the backend
reads each one and an LLM writes a short **title**, a **description**, **tags**, a few actionable
**intents** (open, copy, call, add-to-calendar…), and decides which of your **spaces** it fits.
Everything lands in one **masonry feed**. **Spaces** are themed shelves; making one pulls in
matching existing saves as dismissable **suggestions**. A **Tidy** mode lets you swipe through your
camera roll (keep / delete / save-into-Thingy). The whole thing should feel warm, quiet, and
springy.

Users: individuals curating their own stuff. Single-user data scope — every read/write is scoped
to the signed-in user.

---

## 2. Design language

Material 3 **Expressive**. Embrace it: spring-based motion, bold shapes, large rounded corners,
expressive typography, shared-element transitions, predictive back. This is a deliberate
*re-skin* of Amber's iOS-flavored look into Material — do not try to reproduce iOS glass/blur
chrome; translate the *intent* (warm, tactile, animated) into Material idioms.

### 2.1 Theming & the color toggle (a required feature)

Two color sources, user-selectable in a setting, defaulting to Dynamic:

- **Dynamic (Monet)** — `dynamicLightColorScheme` / `dynamicDarkColorScheme(context)`. **API 31+
  only** (`minSdk` is 29). On API < 31 this is unavailable: **hide the toggle entirely and fall
  back to Amber**, never show a dead switch.
- **Amber** — the app's own warm palette, generated from the seed below via the
  [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) (paste
  the full 30-role light/dark schemes; don't hand-guess roles).

Wrap the app in `MaterialExpressiveTheme(colorScheme = …, motionScheme = MotionScheme.expressive())`.
Store the choice in a `datastore-preferences` key (`enum ColorSource { DYNAMIC, AMBER }`), expose as
a `Flow`, `collectAsState` at the theme root. Follow system light/dark (`isSystemInDarkTheme()`).

**Amber palette seed** (from Amber's `unistyles.ts`; use as the theme-builder seed / spot-check
against these):

| Role (light → dark) | Light | Dark |
|---|---|---|
| primary / seed | `#e6a23c` | `#e6a23c` |
| background | `#faf6ee` | `#191510` |
| surface | `#fffdf8` | `#231e16` |
| surface (muted) | `#f3ecdd` | `#2c261c` |
| on-surface (foreground) | `#2b2418` | `#f4eddd` |
| muted text | `#8d8271` | `#a2977f` |
| faint text | `#b5aa97` | `#6f6650` |
| primary container (soft) | `#f7e8cd` | `#3a2f1c` |
| on-primary-container | `#9a6416` | `#f0c078` |
| error / danger | `#c05a3a` | `#e07a58` |
| outline (border) | `#ece3d1` | `#332c20` |

### 2.2 Shape, spacing, type

- **Spacing unit:** 8dp (Amber's `gap(n) = n*8`). Use an 8-grid.
- **Corner radii:** sm 8, md 11, lg 16, xl 24 (dp). Map to M3 shape tokens (small/medium/large);
  Expressive's larger corners are welcome.
- **Fonts:** Amber uses `Satoshi` (regular/medium/bold) for body and `Exposure` for display.
  Copy the font files from Amber's assets into `res/font/` and wire a `FontFamily`, or pick close
  M3 equivalents if you'd rather not ship the custom faces. Display face is used for screen titles.

### 2.3 Motion & haptics

- Card entrance: staggered fade+rise (Amber uses `FadeInDown` with `index * 60ms` delay). In
  Expressive use `AnimatedVisibility` / item-placement animations with spring specs.
- Suggested-badge dismissal: spring "pop off" (`ZoomOut.springify`, damping ~14, stiffness ~300).
- Fire a light haptic on: successful save, accepting a suggestion, mode switches, and each Tidy
  commit. Use `LocalHapticFeedback`.

---

## 3. Data model

Three collections in Firestore (mirror Amber's `schema.ts` — keep field names & enums identical so
the AI/backend contract doesn't drift). Every doc carries `userId`; **enforce ownership in Firestore
security rules**, not just client code.

### `items`
| field | type | notes |
|---|---|---|
| `userId` | string | owner (auth uid) |
| `type` | enum `image \| link \| note` | |
| `status` | enum `processing \| ready \| failed` | set `processing` on create; AI flips to `ready`/`failed` |
| `title` | string? | AI: short, 2–4 words, no trailing period |
| `description` | string? | AI: 1–2 sentences |
| `url` | string? | link items |
| `storagePath` | string? | Cloud Storage path (Amber's `storageId`) |
| `imageUrl` | string? | resolved download URL for display |
| `heroImageUrl` | string? | OG image for links |
| `aspectRatio` | number? | preview shape; images capture on upload, links read from OG |
| `capturedAt` | number? | EXIF date (images) |
| `latitude`,`longitude` | number? | EXIF GPS (images); always set together — for the map |
| `isSticker` | boolean? | die-cut transparent PNG (from subject cutout) |
| `tags` | string[] | AI: 4–8 lowercase, 1–2 words each |
| `content` | string? | extracted article body (links), paragraph-separated |
| `siteName` | string? | link source label |
| `note` | string? | note items |
| `intents` | Intent[]? | AI actions — see §3.1 |
| `products` | Product[]? | user-triggered shopping results (title,url,price?,merchant?,thumbnailUrl?) |
| `productsStatus` | enum `searching \| ready \| failed`? | drives the "Find links" button |
| `searchText` | string | denormalized title+desc+tags+note for search |

### `spaces`
| field | type | notes |
|---|---|---|
| `userId` | string | |
| `name` | string | |
| `description` | string? | |
| `dynamic` | boolean? | if true, AI keeps suggesting fitting saves; default true on create |

### `spaceItems` (join)
| field | type | notes |
|---|---|---|
| `userId`, `spaceId`, `itemId` | | |
| `status` | enum `suggested \| saved \| dismissed`? | **AI may only ever create/touch `suggested`; `saved`/`dismissed` are user-owned.** absent = `saved` |
| `intents` | Intent[]? | purpose-steered actions scoped to THIS membership (see §5) |

### 3.1 Intent (closed union — mirror exactly)
`kind ∈ { open_url, copy, web_search, open_maps, call, email, message, add_event }`, plus
`label` (1–3 words) and `value` (payload). See §7 for how each kind executes on Android.

---

## 4. Backend (Firebase)

Amber's `convex/*.ts` logic is being **relocated**, not reinvented. Read `convex/ai.ts`,
`items.ts`, `spaces.ts` for exact behavior.

### 4.1 Where each piece runs

- **Firestore + Storage + Auth:** direct client SDK, with security rules enforcing `userId` ownership
  and the `spaceItems.status` write rule (client can write `saved`/`dismissed`; only Functions write
  `suggested`).
- **Cloud Functions (TypeScript/Node, `functions/`):** the AI pipeline. This code is nearly a copy of
  `convex/ai.ts` — Readability (`@mozilla/readability` + `linkedom`), the image-header aspect-ratio
  reader (pure byte parsing, port as-is), OG metadata extraction, the Gemini structured-output calls,
  intent sanitizing, space recommendation, purpose steering, SerpAPI product search. Triggered by:
  - Firestore `onCreate` for a new `item` (status `processing`) → classify → write `ready`.
  - Firestore `onCreate` for a `space` (and on dynamic toggling on) → `recommendForSpace`.
  - When an item is filed into a space → `steerItemForSpace`.
  - Callable function for the user-pressed "Find links" (SerpAPI).
- **Firebase AI Logic (`firebase-ai`, client-side):** optional — only viable for the pure image/note
  classification (no server fetch needed). The **link pipeline must stay in Functions** (URL fetch,
  Readability, SerpAPI keys can't live on-device). Simplest: run *all* classification in Functions
  for consistency, treat `firebase-ai` as a later optimization.

### 4.2 The model & structured output

Amber uses `google/gemini-3.1-flash-lite` via the Vercel AI Gateway with Zod schemas. In Functions
you can keep the exact Vercel AI SDK setup, or use the Gemini API directly with a JSON
`responseSchema`. Keep the **schemas, prompts, and intent-sanitizing rules identical** to `ai.ts`
(they encode hard-won behavior: title style, "never invent a post id", intent payload validation).

### 4.3 Search

Firestore has no full-text search. For a single user's saves at hobby scale, **client-side filtering
over the loaded `items` (match `searchText`) is the lazy correct choice** — no extension. If the set
ever grows big enough to feel slow, add the Algolia Firestore extension behind the same query. (Amber
used Convex's `searchIndex` + a lexical `similarItems`; the "More like this" strip can be a simple
tag-overlap ranking client-side to start.)

---

## 5. The item lifecycle (behavioral spec)

1. **Save.** User adds a link/note/image (optionally pinned to a space). Client writes an `item`
   with `status: processing` (+ a `spaceItems` `saved` row if pinned) and, for images, uploads the
   file to Storage first.
2. **Classify.** Function fetches/extracts (links) or reads content (images/notes), calls Gemini,
   writes `title/description/tags/intents/aspectRatio/heroImageUrl/siteName/content`, maps returned
   space names → suggested memberships, flips `status: ready`. On error → `status: failed`.
3. **Suggest.** Only **dynamic** spaces are visible to the classifier; matches become `suggested`
   `spaceItems` rows (pending the user's yes/no). Creating a space runs a one-off recommendation over
   existing ready items (max ~8 high-confidence picks).
4. **Steer.** When an item enters a space (direct add or accepted suggestion), a light pass proposes
   up to 3 **space-scoped** intents written to that membership row (e.g. the same couch gets a "Shop
   this" link only inside an "apartment shopping" space).
5. **Products.** The detail screen's "Find links" button triggers a callable Function → SerpAPI
   Google Shopping → stores up to 5 product cards on the item.

---

## 6. Navigation & screens

Single-activity Compose, `navigation-compose`. Auth gate + first-run onboarding gate wrap
everything (mirror Amber's `Stack.Protected`).

**Bottom navigation (M3 Expressive nav bar), 4 destinations:**

1. **Home** — masonry feed (`square.grid.2x2`)
2. **Spaces** — cover-stack grid (`rectangle.stack`)
3. **Tidy** — camera-roll swipe deck (`photo.stack`)
4. **Search** — search field + results (search role)

**Other destinations:** `add` (bottom sheet), `newSpace`/editSpace (sheet/screen), `manageSpaces`
(membership toggles for one item), `profile`/settings (sheet — houses the color toggle), `camera`
(full screen), `item/{id}` (detail, horizontally swipeable across the source list), `space/{id}`
(a space's feed), `map` (photo locations). Use a global **+ FAB** on Home/Spaces to open `add`.

### 6.1 Home
- Two-column `LazyVerticalStaggeredGrid` of item cards, newest first, staggered entrance animation.
- Pull-to-refresh is unnecessary (Firestore is live) but harmless.
- Empty state: friendly title + message ("everything lands in one calm feed").
- **Card → detail** via a **shared-element / container transform** (Expressive supports this; it
  replaces Amber's "Apple zoom" into the detail hero).

### 6.2 Item card (`item-card.tsx`)
Three faces by `type`:
- **image / link-with-hero:** image with `aspectRatio` (clamp 0.5–2.0; link default 1.91, image 1).
  Non-stickers sit in a **white matted frame** with a soft shadow; **stickers** render with no frame,
  `contentFit=contain`, and a shadow that hugs the silhouette (overflow visible).
- **link without hero / note:** a text face — muted surface (note face tinted with primary-soft),
  the title/note/host in a few lines; a small link glyph for links.
- **Caption row:** bold title (1 line); for links a host row with an up-right arrow; an **overflow
  (⋮) menu** on the right.
- **Overlays:** a `processing` spinner while classifying; a **suggested "sparkle" badge** (top-right)
  when this card is a pending suggestion in a space — tapping it accepts (badge springs off).
- **Long-press → context menu** (M3 dropdown, Expressive): Share / Remove-from-space / Delete, or for
  a suggestion: Add-to-space / Dismiss. Destructive items styled as error.

### 6.3 Item detail (`item-detail.tsx`)
Scrollable. Order: **hero** (matted frame for photos, full-bleed for stickers, capped at ~55% screen
height so text isn't buried) → for links a tappable **source row** (opens browser) → **intent chips**
(item intents + this-space's steered intents, deduped) → **description** (centered) → **tag chips** →
**space chips** + an "Add to space / Spaces" chip → **Shop** section ("Find links" button → product
cards, or a "Finding links…" state) → for links the **article reader** (paragraphs, selectable) → a
**"More like this"** two-column mini-grid.
- Detail pages are a **horizontal pager** across the same ordered list the card came from (home /
  space / search), so you can swipe between saves. Keep pages light (Amber memoizes; the article can
  be 100+ paragraphs).
- `processing` shows "Thingy is reading this…"; only `ready` items show spaces/products/similar.

### 6.4 Spaces list (`(spaces)/index.tsx`)
Two-column staggered grid of **"cover stacks"**: each space is a hand-dropped pile of 3 same-size
cards — 2 blank tilted cards behind, the newest member's image barely rotated on top (seeded jitter
so each pile is stable but unique). Empty space → dashed placeholder cover. Caption: name + ⋮ menu
(Delete → confirm dialog: "Your saves stay in Home — only the shelf goes away."). A pile fronted by a
not-yet-accepted suggestion wears the sparkle badge. Card → `space/{id}` via shared-element transform.
Empty state prompts making the first space.

### 6.5 Space detail (`space/{id}`)
The space's masonry feed (members + suggestions, suggestions wearing the sparkle). Header shows name;
allow **Accept all** suggestions, edit (→ `newSpace?id=`), and an add-into-this-space entry (pins new
saves to the space). Removing an item only removes membership; the save stays in Home.

### 6.6 New / edit space (`new-space.tsx`)
One form, two modes (`newSpace` vs `newSpace?id=`). Fields: **name** (autofocus on create) and a
**Dynamic** toggle (M3 Switch, on by default) with hint "Thingy keeps suggesting things that fit".
Primary button "Create space" / "Save changes"; success haptic then back. On create, backend runs the
recommendation pass.

### 6.7 Add (`add.tsx`)
A bottom sheet with a menu of four actions (large icon + label): **Note**, **Article**, **Photos**,
**Camera**. Note/Article swap the sheet to a composer with an animated title ("Save something" →
"New note" / "Save an article"), a text field (article field **prefills from a URL on the clipboard**,
url keyboard; note field multiline), Back + Save actions. Photos → system multi-image picker (limit
10, read EXIF date + GPS). Camera → the camera screen. If opened from a space, everything saved is
pinned to that space.

### 6.8 Camera (`camera.tsx`)
Full-screen CameraX preview. Controls: close (X), flip camera, a **shutter**, a library shortcut.
Two capture modes toggled by a **horizontal swipe over the preview** (and by tapping PHOTO / STICKER
labels, active label animates to amber):
- **PHOTO** — capture → save as an image item.
- **STICKER** — capture → run **subject segmentation** (ML Kit) to cut the subject onto transparent
  background → save as `isSticker` PNG. If no subject found, alert and stay. **Hide the STICKER label
  entirely if segmentation is unavailable.** (Amber's cutout also draws a white die-cut outline +
  drop shadow; reproducing that outline is optional polish — see the Swift module for the recipe.)

### 6.9 Search (`(search)/index.tsx`)
A search field (M3 search bar, Expressive) + debounced (~250ms) query over `searchText`; results in
the masonry feed. Empty query → "Find anything" hint. No matches → "Nothing yet".

### 6.10 Tidy (`(tidy)/index.tsx` + `lib/tidy/*`) — the signature interaction
Swipe through the camera roll one photo at a time to declutter.
- **Permission gate** first (request; if permanently denied, deep-link to app settings). Handle
  "limited access" with a banner.
- **Source picker** (All Photos or a specific album) in the top bar; remembers the choice.
- **A card deck** of the current batch. Per card, a pan gesture with three commits (Amber's
  `card-animation.tsx` is the reference for the physics — port the feel, not the RN API):
  - swipe **right** past ¼-width → **Keep**
  - swipe **left** past ¼-width → **Delete** (queued, not immediate)
  - swipe **up** past ⅕-height → **Save into Thingy**
  - dominant axis wins on release; under threshold springs back. Fling the committed card fully
    off-screen with a short spring/timing (don't use a long decay — it makes the next card feel laggy).
    Cards behind scale/reveal as the top card leaves (`deck-animation.tsx`).
- **Header controls:** Undo (left, appears after a decision), Delete-now (right, with a live
  pending-count badge). A centered `n / total` progress counter.
- **Deletes are batched** and committed when: the user taps Delete, finishes the batch (Continue), or
  leaves the screen. Use MediaStore delete (which shows the system trash confirmation on Android).
- **Batch-done** state: a summary (kept/saved/deleted counts) + Continue → commit + load next batch.
- Use `LocalHapticFeedback` for threshold crossings and commits.

### 6.11 Onboarding (`onboarding.tsx`)
First-run: wordmark + slogan "Save it for later.", three animated feature rows (staggered rise), two
permission buttons (Camera, Photos — show "Ready" when granted), and a "Start saving" CTA that marks
onboarding complete (persist the flag in DataStore).

### 6.12 Profile / Settings
Houses account (Firebase Auth sign-out) and **the color-source toggle** (§2.1) — Dynamic vs Amber,
hidden on API < 31.

### 6.13 Share-in
Register an `ACTION_SEND` (text/image) intent-filter so other apps can share into Thingy →
route to the add/ingest flow (Amber's `share.tsx` is the behavioral reference).

### 6.14 Map
A Google Map showing pins for image items that have `latitude/longitude`; tapping a pin opens the
item. (Amber's `map.tsx`.)

---

## 7. Intents on Android (`intents.ts`)

Each `kind` maps to a platform action (Amber uses iOS/Expo primitives; use Android `Intent`s):

| kind | Android action |
|---|---|
| `open_url` | `ACTION_VIEW` the https URL (lets an installed app deep-link; fall back to Custom Tab) |
| `copy` | `ClipboardManager.setPrimaryClip` + success haptic |
| `web_search` | Custom Tab to `google.com/search?q=…` |
| `open_maps` | `geo:0,0?q=…` (or maps URL) |
| `call` | `ACTION_DIAL` `tel:` (digits only) |
| `message` | `ACTION_SENDTO` `smsto:` |
| `email` | `ACTION_SENDTO` `mailto:` |
| `add_event` | `ACTION_INSERT` on `CalendarContract.Events` with the title (user picks date/time) |

---

## 8. Native replacements (iOS → Android)

| Amber (iOS) | Thingy (Android) |
|---|---|
| Subject-lift (Vision `VNGenerateForegroundInstanceMaskRequest` + CoreImage matte) | **ML Kit Subject Segmentation** — on-device, first-party. The white-outline die-cut is optional polish. |
| Progressive/gradient blur, glass effect | `Modifier.blur` / `RenderEffect`; or just lean on Expressive's opaque bold surfaces and drop the glass |
| SF Symbols | Material Symbols / `material-icons-extended` |
| Native tabs, form-sheets, native search bar | M3 `NavigationBar`, `ModalBottomSheet`, `SearchBar` |
| MMKV-persisted TanStack cache | Firestore offline persistence |
| Clerk auth | Firebase Auth (Google via Credential Manager) |

---

## 9. Build order (suggested)

1. Convert template to Compose + Firebase (see CLAUDE.md "Current state").
2. Theme system + color toggle (§2.1) — get Expressive + Amber/Monet switching first; it's the
   whole point of the port and it's cheap.
3. Firebase Auth + onboarding gate.
4. Firestore data layer + repositories + security rules (§3).
5. Home masonry + item card + detail (read-only first).
6. Add flow (note/article/photos) → Cloud Function classification pipeline (§4).
7. Spaces (list, detail, create, suggestions/steering).
8. Search, map, share-in.
9. Camera + sticker (ML Kit).
10. Tidy deck (the fun one — save for when the rest works).

Verify each screen on an emulator (adb / MCP tooling) before moving on. It's a hobby — prioritize the
bits that look good (theme, feed, detail transitions, Tidy swipe).
