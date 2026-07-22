# Thingy Feature Roadmap & Implementation Plan

This document outlines the research, implementation steps, and testing strategies for the upcoming major features requested to elevate Thingy from a simple save-hub to a "living" personal intelligence tool.

---

## 1. Ask-Your-Stuff (Natural Language Search)
**Goal:** Enable true semantic search so users can query their saves naturally (e.g., "that sourdough article") instead of relying on exact substring matches.

### Research Findings
- **Gemini Embeddings:** The Google AI / Vertex AI SDK for Android supports generating text embeddings using `gemini-embedding-001`. This model converts text (like an item's title, AI-generated summary, and extracted notes) into a high-dimensional vector.
- **Firestore Vector Search:** Firebase recently introduced native Vector Search for Android. We can store the embedding vector in a `VectorValue` field on each `Item` document in Firestore.
- **Execution:** We will use `collection.findNearest("embedding", queryVector, 10, DistanceMeasure.COSINE)` to retrieve the top 10 most semantically relevant saves for any user query.

### Implementation Plan
1. **Ingestion Upgrade:** Update `Classifier.kt` to generate an embedding for every new item using the `gemini-embedding-001` model based on its AI-analyzed content.
2. **Schema Update:** Add `embedding: List<Double>?` to the `Item` data class.
3. **Search Screen UI:** Update the search bar to detect natural language. When the user types, generate an embedding for their query on-device, then pass it to Firestore's `findNearest`.
4. **Answer Generation:** Pass the top 3-5 retrieved items to Gemini `gemini-1.5-flash` with the prompt: "Answer the user's query based ONLY on these saved items" to provide a 1-line answer above the results.

### Testing Plan
- Create test items with intentionally ambiguous text (e.g., "A photo of bread"). Search for "sourdough baking" and assert the item is returned in the top 3 results via vector proximity.
- Assert that Firestore composite indexes for vector fields are successfully deploying and returning fast results under 300ms.

---

## 2. Resurfacing & "Remind Me Later"
**Goal:** Prevent saves from dying in the feed by resurfacing them on anniversaries or user-scheduled snoozes.

### Research Findings
- **WorkManager:** `androidx.work:work-runtime-ktx` is the modern standard for guaranteed background execution on Android, requiring no backend.
- **Reminders (Snooze):** For exact or near-exact reminders (e.g., "Saturday morning"), we will enqueue a `OneTimeWorkRequest` with an initial delay calculated from the current time to the target time.
- **On This Day:** A `PeriodicWorkRequest` scheduled to run once daily (e.g., at 9 AM) that queries the local `ItemRepository` for items where `createdAt` matches the current day/month, but a previous year.

### Implementation Plan
1. **Snooze UI:** Add a "Snooze" action to the `ItemDetailScreen` bottom bar with options like "Tomorrow", "Next Weekend", etc.
2. **Worker Setup:** Implement a `NotificationWorker` that builds and fires a system notification using `NotificationManagerCompat`.
3. **Deep Linking:** Ensure tapping the notification fires a deep link Intent that directly opens the `ItemDetailScreen` for the snoozed item.

### Testing Plan
- Schedule a 1-minute test reminder and put the app in the background. Assert the notification fires and successfully deep-links into the item detail.
- Mock the system clock to exactly 1 year after an item's creation date and manually trigger the daily PeriodicWorker to verify the "On This Day" logic.

---

## 3. Smart Space Suggestions
**Goal:** Remove organizational friction by proactively suggesting new Spaces based on clustered saves (e.g., "You have 8 recipe saves").

### Research Findings
- Items already possess AI-generated `tags` from the Gemini ingestion pipeline.
- We can perform a lightweight greedy clustering or frequency analysis on these tags locally.

### Implementation Plan
1. **Frequency Analysis:** Inside `LibraryViewModel`, observe the user's total items. Extract all `tags` and build a frequency map.
2. **Threshold Trigger:** If a specific tag (e.g., "Recipes", "Interior Design") appears in > 5 ungrouped items, emit a suggestion state.
3. **Suggestion UI:** Render a prominent "Suggestion Card" at the top of the Home feed: "Create a 'Recipes' space for your 8 items?".
4. **1-Tap Creation:** On tap, instantly create the Space in Firestore and batch-write all 8 item IDs into the `spaceItems` collection.

### Testing Plan
- Mock 6 items with the tag "DIY". Assert the UI emits exactly one suggestion card for "DIY".
- Accept the suggestion and verify that the feed updates, the Space is created, and the items are successfully moved/tagged.

---

## 4. Share a Space as a Collage
**Goal:** Render a visually stunning collage of a Space's covers into a Bitmap for native Android sharing.

### Research Findings
- **GraphicsLayer (Compose 1.7+):** Jetpack Compose now supports rendering composable trees directly to bitmaps using `GraphicsLayer` and `GraphicsLayer#toImageBitmap()`.
- **Android Intent:** We can save the resulting Bitmap to the `cacheDir` and use `FileProvider` to yield a `content://` URI for `ACTION_SEND`.

### Implementation Plan
1. **Collage Composable:** Build an off-screen `SpaceCollageLayout` that arranges the top 4-6 item hero images beautifully (e.g., in a staggered grid) with the Space title overlaid.
2. **Render to Bitmap:** Wrap the layout in a `Modifier.drawWithCache` or manually record it into a `GraphicsLayer`, then suspend and await `toImageBitmap()`.
3. **Share Intent:** Save the bitmap to `context.cacheDir`, generate a secure URI via `FileProvider`, and launch `Intent.createChooser()`.

### Testing Plan
- Trigger the share action on a Space with 5 items. Intercept the `ACTION_SEND` intent in an Espresso test and assert the payload is a valid image URI.
- Verify the generated Bitmap dimensions and visual output locally.

---

## 5. Per-Item Ambient Theming
**Goal:** Tint the detail screen background and accents using the dominant colors of the item's hero image.

### Research Findings
- **androidx.palette:** The `androidx.palette:palette-ktx` library extracts prominent colors (Muted, Vibrant, Dominant) from bitmaps.
- **Coil Integration:** We can hook into Coil's `onSuccess` listener, convert the `Drawable` to a `Bitmap`, and pass it to `Palette.from(bitmap).generate()`.

### Implementation Plan
1. **Dependency:** Add `implementation("androidx.palette:palette-ktx:1.0.0")`.
2. **State Hoisting:** In `ItemDetailScreen`, add a `var dominantColor by remember { mutableStateOf<Color?>(null) }`.
3. **Extraction:** Update the `AsyncImage` request builder in the `Hero` composable to extract the palette asynchronously on success.
4. **Theme Application:** Animate the `Scaffold` background color to a severely darkened/desaturated version of the dominant color, and tint the text/icons using the vibrant counterpart to create a bespoke, immersive feel.

### Testing Plan
- Load an item with a strictly red image. Assert the calculated ambient color matches a red hue.
- Ensure the extraction runs asynchronously on the IO dispatcher so it doesn't drop frames during the transition animation into the detail screen.
