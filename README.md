# ClipMe

**100% on-device** video clipper for YouTube and Twitch. Paste a stream/VOD link,
mark a few windows, lay out a 9:16 short with a game + facecam dual box, on-device
word-by-word captions, hook text and stickers — then export to `Movies/ClipMe`.

Everything runs on the phone. No accounts, no cloud, no uploads.

---

## Features

- **3-tab navigation** — `+ Create` · `My clips` · `Trending`
- **3-step Create flow** with a `? HELP` modal on every step:
  1. **Stream** — paste a YouTube or Twitch link, resolve it, see a live preview frame, download a 2-minute window
  2. **Clips** — drag handles on a timeline to pick 5 s–3 min windows; no overlapping clips
  3. **Layout** — pick a preset (split, circle cam, game-only, blur, big cam, rounded cam, cam+game), hook text, captions, stickers, quality
- **Dual-box cropping** — lime `GAME` box + pink `CAM` box, dragged right on the preview frame
- **Auto facecam** — ML Kit face detection positions the pink CAM box automatically (falls back to top-right corner)
- **On-device captions** — Vosk speech model (`vosk-model-small-en-us-0.15`, downloaded once, then offline) → word-accurate caption lines; text renderer bakes them into the video
- **Batch export queue** — `Clip it — N clips` foreground service notification with a live progress bar and an expandable log console; Cancel action; final summary when done
- **My clips** — grid library of every export with rename / delete / share / play
- **Trending** — offline inspiration feed that maps into the built-in layout presets
- **Deep links** — opening a youtube.com / youtu.be / twitch.tv URL in ClipMe prefills the Create flow

## Stack

| Concern | Library |
|---|---|
| UI | Jetpack Compose (Material 3) |
| Stream extraction | NewPipeExtractor (YouTube) + custom Twitch resolver (GraphQL + usher HLS) |
| Playback / export | AndroidX Media3 (ExoPlayer, Transformer, Effect) with an OpenGL GLES pipeline |
| Rendering | `GlEffect` / `BaseGlShaderProgram`, `BitmapOverlay`, `Presentation` (9:16 output) |
| Facecam | ML Kit face detection |
| Captions | Vosk offline ASR |
| Download | HLS window downloader (custom, OkHttp) over the resolved manifests |

Output is `.mp4` (1080p or 720p, 9:16) written by
`Transformer.start(EditedMediaItem, path)` and published to the gallery.

No watermark is ever generated; the launcher icon is the only image resource and is
never referenced by export code.

## Project structure

```
app/src/main/java/com/clipme/app/
├── MainActivity.kt            # Host: 3-tab bottom nav, deep links, notification permission
├── ClipMeApp.kt               # Manual service locator (streams, captions, export)
├── model/Models.kt            # StreamInfo, ClipRange, ExportSettings, ExportJob, …
├── net/HttpClient.kt          # OkHttp wrapper (downloads, resume, cancel)
├── resolve/                   # NewPipe + Twitch resolvers, frame grab, HLS window download
├── captions/                  # Vosk model manager, PCM extraction, word-level CaptionEngine, face detection
├── data/ClipStore.kt          # MediaStore-backed library of exported clips
├── export/                    # GL compositor, batch export engine, foreground service + log console
├── ui/create/                 # 3-step create flow, stickers editor, help modals, CreateViewModel
├── ui/myclips/                # Library grid
├── ui/trending/               # Offline trending feed
├── ui/theme/                  # Dark palette (surface #0D0F0E, lime #B8FF00, pink #FF4D8D)
└── util/                      # FileUtils, BitmapUtils, TextRenderer, image loaders, stopwatch
```

## Building

Requires **Android Studio (Meerkat / 2024.2 or newer)** or a machine with the
Android SDK, JDK 17 and Gradle.

1. Open the `ClipMe/` folder in Android Studio.
2. Wait for Gradle sync to finish (first sync downloads dependencies — this can take a while; the Vosk + NewPipe + ML Kit artifacts are large).
3. Run the `app` configuration, or:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install the resulting `app/build/outputs/apk/debug/app-debug.apk`.

> **On-device requirements:** the Vosk caption model (~40 MB) is fetched once
> over the network on first use and cached in the app's files directory. Everything
> after that — extraction, captions, rendering, export — runs fully offline.

## Notes

- API 26+ (Android 8.0+), target SDK 35.
- `POST_NOTIFICATIONS` is requested on launch so the export foreground service can
  show its progress; if it is denied the export still runs, just without a notification.
- This repository was authored in an environment without a JDK/Android SDK, so the
  code has been syntax-checked but **not compiled** here. Build it in Android Studio
  or CI as described above.
