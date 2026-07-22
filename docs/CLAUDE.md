# CLAUDE.md — Thingy

Guidance for Claude Code when working in this repository.

## What this is

**Thingy** is an Android-native port of **Amber**, a "save-it-for-later" hub. Users capture
links, images, and notes; a backend fetches/extracts content and an LLM classifies each item
(title, description, tags, actionable "intents", and which "spaces" it belongs to). Everything
renders as a masonry feed. Items organize into **spaces** (themed collections); creating a space
retroactively suggests matching existing saves.

Thingy keeps Amber's product and backend *logic* but replaces:

- **UI layer:** React Native → **Jetpack Compose with Material 3 Expressive**.
- **Backend host:** Convex → **Firebase** (Auth, Firestore, Cloud Storage, Cloud Functions, Firebase AI Logic).
- **iOS native modules:** Vision/CoreImage → **Android platform equivalents** (ML Kit).

Platform target: **Android only.** No iOS, no cross-platform. Lean into the Material aesthetic.

> **This is a hobby side-project.** Optimize for look/feel and fun, not enterprise robustness.
> Alpha dependencies are fine (Google alpha is stable enough). Extra libraries are fine.
> Prefer the path that looks good with the least fighting.

## The Amber reference

`@AMBER_REFERENCE.md` maps every feature to its original source file (absolute paths into
`/Users/leo/AndroidStudioProjects/amber`). **When a spec in `PRD.md` is ambiguous, read the
Amber source before guessing.** The `PRD.md` describes *what* to build; Amber shows *exactly how*
it currently behaves.

## Product spec

`@PRD.md` is the full product/interaction/UX spec — screens, gestures, data model, AI pipeline,
theming. Read it before building any feature.

## Toolchain & commands

Standard Android Studio / Gradle project.

- `./gradlew assembleDebug` — build.
- `./gradlew installDebug` — build + install on a running device/emulator.
- `./gradlew lint` / `./gradlew ktlintCheck` — lint (add ktlint if you want it).
- Use the **dart/Argent MCP tools or `adb`** to drive the emulator, screenshot, and verify UI —
  always inspect the view tree before asserting on UI, don't guess.

Firebase backend:

- `firebase emulators:start` — run Auth/Firestore/Storage/Functions locally.
- `firebase deploy --only functions` — deploy the AI Cloud Functions.
- Cloud Functions live in `functions/` (TypeScript, Node) — see PRD "Backend".

## Current state of this repo

The template that ships here is a **plain Views (AppCompat/Material) template — NOT Compose.**
Before any app code, convert it:

1. Add Kotlin + Compose to `libs.versions.toml` and `app/build.gradle.kts`
   (`org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `buildFeatures { compose = true }`).
2. Add the `com.google.gms.google-services` plugin + `google-services.json`.
3. Replace the AppCompat `MainActivity` with a `ComponentActivity` + `setContent { }`.
4. Delete the Views theme XML / example test scaffolding once Compose runs.

Existing template facts (keep unless a reason to change): namespace/appId `com.wolfeleo2.thingy`,
`minSdk 29`, `targetSdk 36`, `compileSdk 36`, AGP `9.2.1`. **`minSdk 29` matters:** dynamic color
(Monet) needs API 31 — gate it (see PRD "Theming").

## Library versions

Pin stable libs; take **latest alpha** for anything Expressive. Resolve exact alpha suffixes at
setup time (they churn) — the constraint is what matters, not the digits.

| Purpose | Dependency | Version |
|---|---|---|
| Kotlin | `org.jetbrains.kotlin.android` | 2.1.x (latest 2.1) |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` | matches Kotlin version |
| Compose BOM | `androidx.compose:compose-bom` | latest stable (BOM manages compose-ui/foundation versions) |
| **Material 3 Expressive** | `androidx.compose.material3:material3` | **latest `1.4.0-alpha`+** (must include `MaterialExpressiveTheme` / `MotionScheme`) — override the BOM's pin |
| Material icons | `androidx.compose.material:material-icons-extended` | via BOM |
| Activity | `androidx.activity:activity-compose` | latest stable |
| Navigation | `androidx.navigation:navigation-compose` | latest stable (Nav 2). Nav 3 optional — not worth the churn for a hobby |
| Lifecycle | `androidx.lifecycle:lifecycle-{runtime,viewmodel}-compose` | latest stable |
| Settings storage | `androidx.datastore:datastore-preferences` | latest stable |
| Image loading | `io.coil-kt.coil3:coil-compose` + `coil-network-okhttp` | Coil **3.x** (the `expo-image` equivalent) |
| Firebase | `com.google.firebase:firebase-bom` | latest; then `firebase-auth-ktx`, `firebase-firestore-ktx`, `firebase-storage-ktx` |
| **Firebase AI Logic** | `com.google.firebase:firebase-ai` | latest (client-side Gemini; on-device classification path only — see PRD) |
| Sign-in | `androidx.credentials:credentials` + `googleid` (Credential Manager) | latest — Google sign-in for Firebase Auth |
| **Subject cutout** | `com.google.mlkit:subject-segmentation` | latest beta (replaces iOS Vision) |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | latest stable |

Free (no dependency): masonry = `LazyVerticalStaggeredGrid` (Compose foundation); haptics =
`LocalHapticFeedback` / `View.performHapticFeedback`; blur = `Modifier.blur` / `RenderEffect`;
share-in = an `ACTION_SEND` intent-filter on the activity.

## Architecture (target)

- **Single-activity Compose app.** `ComponentActivity` → `AmberTheme { AppNavHost() }`.
- **Navigation:** `navigation-compose`. Destinations mirror Amber's routes — see PRD "Navigation".
- **State:** per-screen `ViewModel`s exposing `StateFlow`; Firestore listeners → `Flow` →
  `collectAsStateWithLifecycle()`. Firestore offline persistence replaces Amber's MMKV-persisted
  cache, giving the same instant-cold-launch feed.
- **Repositories:** thin wrappers over Firestore/Storage/Functions. **Derive `userId` from
  Firebase Auth on the server (Functions + Firestore rules), never trust a client-supplied id** —
  this mirrors Amber's `requireUserId` rule and is the one security invariant not to relax.
- **AI:** the multi-step pipeline (URL fetch → Readability extract → structured LLM call →
  scheduled follow-ups → SerpAPI) runs in **Cloud Functions** (it cannot run client-side). Firebase
  AI Logic (`firebase-ai`) is only for the pure image/note classification if you want it on-device;
  the link pipeline stays server-side. See PRD "Backend".

## Conventions

- Kotlin idiomatic; `data class` for models; `sealed`/`enum` for closed unions (item type, status,
  intent kind — mirror `schema.ts` exactly so nothing drifts from the backend contract).
- Compose: hoist state, `@Composable` functions `PascalCase`, previews with `@Preview`.
- Colors/spacing/shape come from the M3 theme + a small design-token file — never hardcode hex in
  screens (Amber centralizes this in `unistyles.ts`; do the same here).
- Match surrounding code's style. Keep diffs small. Don't add abstractions before a second caller
  needs them.
