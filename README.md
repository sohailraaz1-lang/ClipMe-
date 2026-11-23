# ClipMe 2.1
On-device video clipper. Push this folder to GitHub, open **Actions**, run "Build ClipMe APK", download the **ClipMe-apk** artifact.

## What changed (LLM fix)
- Download buttons now really download (resumable, progress, cancel, delete, "Use").
- Real LLM engine: llama.cpp compiled in the build (`app/src/main/cpp`), runs GGUF models on CPU.
- AI picks clips: loudness + YouTube "most replayed" heatmap -> speech-to-text of candidates -> LLM ranks them and writes a hook per clip.
- Model links are public (no Hugging Face login). Sizes are real: Qwen 0.5B ~0.5 GB, Gemma 2 2B ~1.7 GB, Llama 3.2 3B ~2 GB, Phi-3 Mini ~2.4 GB.
- Speech models (Vosk) download from the same screen.
- Fixed: yt-dlp init race, real error messages, landscape output, music toggle, share/delete/save, edit screen re-renders, local file picker, caption colour presets.

## If the build says "WITHOUT LLM engine"
The workflow first builds with the native engine, and if that step fails it builds a working app without it. Open the failed step's log and send it to me.

## Tests
`test/` has the headless-browser test run (37 checks) with a mock native bridge.
