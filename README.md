# ClipMe v4 — Live-stream & VOD clipper for Reels / Shorts (1080×1920)

Rebuilt to follow the flow of the screen recording you sent (**Create → Clips → Layout**, floating
**Create / My clips / Trending** bar), plus everything in your master prompt.

```
app/      Expo (React Native, SDK 52) mobile app
backend/  FastAPI + yt-dlp + FFmpeg engine  (no Termux — run it on a PC, VPS or Docker)
```

## Run it
**Backend** (needs ffmpeg on PATH)
```bash
cd backend && pip install -r requirements.txt
cp .env.example .env     # all keys optional
uvicorn server:app --host 0.0.0.0 --port 8000
# or: docker build -t clipme backend && docker run -p 8000:8000 -v clipme:/data clipme
```
**App**
```bash
cd app && npm install && npx expo start
```
Then tap the ⚙ icon → set **Server address** (Android emulator `http://10.0.2.2:8000`, phone: your server's IP/domain) → *Test connection*.
Video playback and saving to gallery need a development build (`npx expo run:android` / EAS), not Expo Go.

## What matches the reference video
| Reference | In ClipMe v4 |
|---|---|
| 3-step stepper, stream/clip summary rows | `CreateScreen` |
| Paste link → "Checking the link… / Getting a frame…" progress with timers + Cancel | `StreamStep`, `/api/resolve` job |
| "How long ago?" keypad time (`3000` = 30:00), Just now, 5/10-min window, Load this part | `ClipsStep` |
| Low-quality window preview, lime trim handles, drag tooltip, loop, mute, Add this clip | `ClipEditor` |
| Clip chips, overlap warning, ← +2 min earlier / +2 min later →, "How moments work" | `ClipsStep` |
| Captions Off/On, layout grid, GAME (lime) / CAM (pink) boxes, Reset to auto, RESULT preview | `LayoutStep` |
| Cam corner (4 arrows), cam size S/M/L, game size 50/50·60/40·70/30 | `LayoutStep` |
| More options: caption style, font, hook text + position, quality | `LayoutStep` (+ 30/60 fps) |
| "Making clips 1/2 … live pieces 5/93", leave the app, notification, View log | render job + push/local notification |
| My clips (play / share / save / delete), Trending feed (All·YouTube·Twitch, game, language, Clip it →) | `MyClipsScreen`, `TrendingScreen` |

## Layouts (10) — all 1080×1920, never stretched
Reference 7: **Split** (50/50, 60/40, 70/30) · **Circle cam** · **Game only** · **Whole screen** (blurred fill) · **Big cam** · **Cam box** · **Cam + game**
Extras: **Cam only** · **Whole + cam** · **Cam banner**

Bubbles/boxes keep clear of the Reels/Shorts UI zones. The GAME/CAM boxes lock to the exact shape each
layout needs, so what is inside the box is exactly what is exported. A layout that needs a facecam but has none falls back to Game only.
*"Cam + game"* wasn't shown in detail in the recording — I built it as two rounded cards (cam over game) on a blurred background.

## Smart clipping
* **Gemini auto-clipper** — key in Settings or `GEMINI_API_KEY`; sends the loaded preview window; returns scored
  `start_sec/end_sec/title/reason/viral_score`; ✓ adds it to the queue, ✕ rejects it.
* **Chat-hype detector** — spike when chatters ≥ N % of viewers (default 5 %, e.g. 400 of 8,000); clip = 12 s before to 25 s after
  (clamped to 10–15 / 20–30). YouTube VOD chat replay supported.
* **GTA + Shorts/Reels retention scoring** applied to every suggestion.

## Verified here
* 16 backend tests pass, including **real FFmpeg renders of all 10 layouts** (H.264 / yuv420p / AAC, exactly 1080×1920, 30 and 60 fps) and caption burn-in.
* Layout math in the app (`layouts.ts`) is checked identical to the renderer across 810 layout/option/source-aspect combinations.
* Gemini parser: fenced JSON, wrappers, junk, missing key, network failure and retries never raise.
* All app files parse; the logic modules type-check in strict mode; all 76 internal imports resolve.

## Not verified here (be aware)
* The React Native UI was **not run on a device** (no emulator/network in my environment). Expect small fixes on first run.
* Real yt-dlp downloads of live/VOD sections and the Trending feed weren't exercised (no internet). Live-stream limits are the platforms':
  YouTube live ≈ up to 5 days back; Twitch only saved broadcasts. Keep yt-dlp updated.
* Trending shows demo data until `TWITCH_CLIENT_ID/SECRET` or `YOUTUBE_API_KEY` are set.
* Push while the app is closed needs an EAS `projectId`; without it you still get a local notification when the app regains control, and clips save to the gallery then.
* I did not copy the reference's "4 free left" credit pill or its branding.
