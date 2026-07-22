# Amber reference map

**Thingy is a Kotlin / Jetpack Compose (Material 3 Expressive) port of Amber**, an
Expo/React-Native + Convex app. When a spec here is unclear, the source of truth is
the original Amber implementation. Go read it — don't guess.

- **Amber repo root:** `/Users/leo/AndroidStudioProjects/amber`
- **Amber's own guidance:** `/Users/leo/AndroidStudioProjects/amber/CLAUDE.md` (architecture,
  toolchain, gotchas). Read it for backend behavior — the *logic* is being reused, only the
  UI/native layer and the backend host (Convex → Firebase) change.

## Feature → source-file map

Read the file next to a feature before porting that feature.

| Feature / concern | Amber source (absolute path) |
|---|---|
| **AI pipeline** (classify, extract, intents, recommend, steer, product search) | `/Users/leo/AndroidStudioProjects/amber/convex/ai.ts` |
| **Data model** (items, spaces, spaceItems, all fields + states) | `/Users/leo/AndroidStudioProjects/amber/convex/schema.ts` |
| **Backend API** (queries/mutations the client calls) | `/Users/leo/AndroidStudioProjects/amber/convex/items.ts`, `/Users/leo/AndroidStudioProjects/amber/convex/spaces.ts` |
| **Auth / userId derivation** | `/Users/leo/AndroidStudioProjects/amber/convex/model/auth.ts` |
| **Theme palette, radii, fonts, spacing** | `/Users/leo/AndroidStudioProjects/amber/src/unistyles.ts` |
| **Home masonry feed** | `/Users/leo/AndroidStudioProjects/amber/src/components/masonry-feed.tsx` |
| **Feed card** (image/link/note faces, sticker, suggested badge, context menu) | `/Users/leo/AndroidStudioProjects/amber/src/components/item-card.tsx` |
| **Item detail** (hero, intents, tags, spaces, products, article reader, "more like this") | `/Users/leo/AndroidStudioProjects/amber/src/components/item-detail.tsx` |
| **Add flow** (note / article / photos / camera) | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/add.tsx` |
| **Camera + sticker capture** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/camera.tsx` |
| **Subject-lift native module** (Vision cutout → ML Kit on Android) | `/Users/leo/AndroidStudioProjects/amber/modules/subject-lift/ios/SubjectLiftModule.swift` |
| **Spaces list** (tilted cover-stack piles) | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/(tabs)/(spaces)/index.tsx` |
| **New/edit space + dynamic toggle** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/new-space.tsx` |
| **Search** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/(tabs)/(search)/index.tsx` |
| **Tidy** (swipe deck over camera roll) | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/(tabs)/(tidy)/index.tsx` |
| **Tidy swipe physics** | `/Users/leo/AndroidStudioProjects/amber/src/lib/tidy/card-animation.tsx`, `deck-animation.tsx` |
| **Intents runner** (open_url / copy / call / maps / …) | `/Users/leo/AndroidStudioProjects/amber/src/lib/intents.ts` |
| **Onboarding** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/onboarding.tsx` |
| **Tab structure** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/(tabs)/_layout.tsx` |
| **Share-in flow** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/share.tsx`, `+native-intent.ts` |
| **Map of saved photo locations** | `/Users/leo/AndroidStudioProjects/amber/src/app/(app)/map.tsx` |

## How to read Amber files efficiently

The `tokensave` MCP server is available in the Amber repo — use `tokensave_context`
with a natural-language query, or `tokensave_read` on a path, instead of dumping whole
files, to save tokens when cross-referencing.
