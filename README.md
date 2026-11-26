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

## Building (GitHub Actions)

1. Create a GitHub repo and upload everything inside this folder (so `build.gradle.kts` is at the repo root, with the hidden `.github` folder included).
2. Open the **Actions** tab, pick **Build ClipMe APK** and press **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **ClipMe-debug-apk** artifact, unzip it and install `app-debug.apk` on your phone (allow "install unknown apps").
4. If the build fails, open the failed step and copy the red error lines.

Local build: Android Studio (2024.2+), JDK 17, Gradle 8.9 -> `gradle assembleDebug`.

## Notes

- API 26+ (Android 8.0+), target SDK 35.
- Export runs as a foreground service: `mediaProcessing` on Android 15, `dataSync` on Android 10-14.
- The Vosk caption model (~40 MB, English only) downloads once on first use.
- The code has not been compiled by its author. Expect to fix a few compile errors from the first CI log.
