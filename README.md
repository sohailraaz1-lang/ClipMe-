# ClipMe 2.2
On-device video clipper. Push this folder to GitHub, open **Actions**, run "Build ClipMe APK", download the **ClipMe-apk** artifact.

## 2.2 - video download fix ("stuck on Downloading 0%")
Causes found by comparing with the working Clipper and DIGICLIPS apps:
- yt-dlp library was 0.17.x (Python 3.8, no JavaScript runtime). Current yt-dlp needs Python 3.9+ and a JS runtime
  (QuickJS) to read YouTube. Now 0.18.x (Python 3.12 + QuickJS), same toolchain Clipper builds with
  (Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1).
- `--download-sections` forced yt-dlp onto ffmpeg: no progress lines (button frozen at 0%) and YouTube throttles it.
  Now normal videos use the native downloader with real progress; only videos > 30 min / live use sections.
- yt-dlp self-update ran in the background while the first download started. Now it finishes (45 s cap, nightly then
  stable, at most every 12 h) before the first download.
- New `Fetcher.kt`: read video info first, then download; tries other YouTube clients if one fails; stall watchdog
  (3 min), timeouts, cancel (tap the grey button), clear error messages, always ends with exactly one result.
- `-k` leftovers (`video.f137.mp4`) could be picked instead of `video.mp4`; fixed.
- Busy button was `pointer-events:none`, so nothing could be cancelled; fixed.

## What changed in 2.1 (LLM fix)
- Download buttons now really download (resumable, progress, cancel, delete, "Use").
- Real LLM engine: llama.cpp compiled in the build (`app/src/main/cpp`), runs GGUF models on CPU.
- AI picks clips: loudness + YouTube "most replayed" heatmap -> speech-to-text of candidates -> LLM ranks them and writes a hook per clip.
- Model links are public (no Hugging Face login). Sizes are real: Qwen 0.5B ~0.5 GB, Gemma 2 2B ~1.7 GB, Llama 3.2 3B ~2 GB, Phi-3 Mini ~2.4 GB.
- Speech models (Vosk) download from the same screen.
- Fixed: yt-dlp init race, real error messages, landscape output, music toggle, share/delete/save, edit screen re-renders, local file picker, caption colour presets.

## If the build says "WITHOUT LLM engine"
The workflow first builds with the native engine, and if that step fails it builds a working app without it. Open the failed step's log and send it to me.

## Tests
`test/` has the headless-browser test run (43 checks, including download failure / cancel paths) with a mock native bridge.
