# Thingy Scalability & Security Audit

This document outlines several medium-to-long-term architectural improvements required as the application scales beyond initial user groups, as well as immediate fixes applied to processing bottlenecks and security vectors.

## 1. Cloudinary Upload Security (Unsigned Presets)

### The Issue
The application currently uses an unsigned Cloudinary upload preset (`ml_default`) embedded directly in the APK (`VideoIngestor.kt` and `ImageIngestor.kt`). Since this preset does not require a backend signature, any malicious actor who extracts the Cloudinary cloud name and preset from the APK can trivially abuse the preset to upload arbitrary files to the Cloudinary account, leading to storage exhaustion and potential billing spikes.

### The Fix
This cannot be fully mitigated in client-side code without introducing a backend signing server. However, it can be heavily sandboxed directly within the Cloudinary Dashboard:
1. **Navigate** to Cloudinary Settings > Upload > Upload Presets.
2. **Edit** the `ml_default` preset (or create a new restricted one).
3. **Lockdown Parameters**:
   * **Allowed Formats:** Restrict strictly to `mp4`, `webm`, `jpg`, `png`, `webp`.
   * **Max File Size:** Cap at ~150MB (matching the client-side validation limit).
   * **Folder Enforcement:** Force all uploads to a specific sandboxed folder (e.g., `thingy_client_uploads/`) to separate them from trusted backend assets.
   * **Moderation (Optional):** Enable auto-moderation to flag/block NSFW or malicious content.

### Metrics of Improvement
- Reduces arbitrary payload attacks by 100% (only valid media allowed).
- Caps financial exposure by strictly bounding file sizes.

---

## 2. Video Classification Constraints (20MB Inline Data Cap)

### The Issue
The application currently passes video data to the Gemini AI model (in `Classifier.kt`) via inline base64 blobs. The Gemini API enforces a strict ~20MB payload limit for inline data. A standard 720p/30-second video clip often exceeds this limit, resulting in the classification request failing silently. This is the root cause of video items occasionally landing in the database as "Untitled" with no AI-generated metadata.

### The Fix
The classification architecture must be migrated to use the **Gemini Files API** for videos.
1. The `VideoIngestor` transcodes the video.
2. The client uploads the transcoded `.mp4` directly to the Gemini Files API, which supports files up to 2GB.
3. The client receives a `fileUri` from Gemini and passes that URI to the `generateContent` prompt instead of the raw inline bytes.

### Metrics of Improvement
- Increases maximum AI-processable video size from ~20MB to 2GB.
- Eliminates "Untitled" video failures for clips nearing the 30-second cap.
- Reduces raw bandwidth required for the API request by passing a reference instead of base64 encoding.

---

## 3. Map Rendering & Synchronization at Scale

### The Issue
Currently, the application relies on fetching all items into memory (`itemRepository.items()`) and rendering them via Compose-based `ViewAnnotation` loops in `MapScreen.kt`. Additionally, the `OfflineImageSyncer` performs a full re-fetch of all items on every app launch. 
At a scale of 5-10 users, this is completely fine. At a scale of thousands of items per user:
- `ViewAnnotation` is a heavy Compose view; thousands of them will drastically drop map framerates.
- Fetching the entire database on launch will bottleneck the UI thread and consume excessive memory and read operations.
- `MapEffect(Unit)` fits the camera bounds once but does not gracefully handle dynamic pagination.

### The Fix
1. **Map Rendering:** Migrate from Compose `ViewAnnotation` loops to native Mapbox `SymbolLayer` or `CircleLayer` powered by a `GeoJsonSource`. This pushes the rendering down to Mapbox's highly optimized OpenGL/Metal engine, allowing it to render 100,000+ points at 60fps effortlessly.
2. **Data Pagination:** Implement Firestore cursor pagination (`startAfter`, `limit`) inside `ItemRepository` to load items in chunks.
3. **Sync Optimization:** The `OfflineImageSyncer` should track a `lastSyncedAt` timestamp locally and only query Firestore for items modified *after* that timestamp, rather than re-evaluating the entire collection on every launch.

### Metrics of Improvement
- **Map Framerate:** Restores solid 60fps regardless of item count.
- **Memory Footprint:** Reduces overhead by decoupling Compose state from map markers.
- **Firestore Reads:** Drops initial launch reads from $O(N)$ (total items) to $O(\Delta)$ (only new items).
