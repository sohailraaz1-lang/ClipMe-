"""
ClipMe engine (pure standard library - importable and testable without FastAPI).

  * 9:16 (1080x1920) FFmpeg filtergraph compiler for all 10 layouts
  * hook text + word-by-word caption (ASS) generation
  * Gemini API auto-clipper with defensive JSON parsing
  * chat-hype spike detector + GTA / Shorts-retention scoring
  * time input parsing ("3000" = 30:00, "1:27:40", "30 min", ...)
"""
from __future__ import annotations

import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional

from layouts import (  # noqa: F401  (re-exported)
    ASPECT, H, PRESETS, W, Box, LayoutSpec, Plan, Tile,
    fit_box_aspect, layout_plan, needs_cam, normalize_preset, target_aspects,
)

REELS_SHORTS_WIDTH, REELS_SHORTS_HEIGHT = W, H
MIN_CLIP_SEC, MAX_CLIP_SEC, MAX_CLIPS = 5.0, 180.0, 10

# Legacy name kept so older code (and the v3 CI) keeps working.
BoxCoords = Box


# --------------------------------------------------------------------------------------
# Request model
# --------------------------------------------------------------------------------------
@dataclass
class ClipRequest:
    url: str
    start_sec: float
    end_sec: float
    preset: str = "split"
    split_ratio: str = "50/50"
    cam_corner: str = "br"
    cam_size: str = "M"
    game_box: Optional[Box] = None
    cam_box: Optional[Box] = None
    auto_captions: bool = False          # OFF by default -> fast export for CapCut
    caption_style: str = "bold"          # bold | clean | box
    font: str = "bold"                   # bold | narrow | grotesk | mono
    hook_text: str = ""
    hook_position: str = "top"           # top | middle | bottom
    quality: str = "balanced"            # fast | balanced | high
    fps: int = 30                        # 30 or 60
    is_live: bool = False
    target_width: int = W
    target_height: int = H

    def spec(self) -> LayoutSpec:
        return LayoutSpec(
            preset=normalize_preset(self.preset), split_ratio=self.split_ratio,
            cam_corner=self.cam_corner, cam_size=self.cam_size,
            game_box=self.game_box or Box(), cam_box=self.cam_box,
        )

    @property
    def duration(self) -> float:
        return self.end_sec - self.start_sec


def validate_clip_times(start: float, end: float) -> Optional[str]:
    if start < 0 or end <= start:
        return "End must be after start."
    d = end - start
    if d < MIN_CLIP_SEC:
        return f"Clips must be at least {MIN_CLIP_SEC:.0f} s."
    if d > MAX_CLIP_SEC:
        return f"Clips can be at most {MAX_CLIP_SEC:.0f} s (3 min) for Reels / Shorts."
    return None


# --------------------------------------------------------------------------------------
# Time helpers
# --------------------------------------------------------------------------------------
def digits_to_seconds(digits: str) -> int:
    """Keypad-style entry: the last 2 digits are seconds, the 2 before are minutes,
    the rest hours. '3000' -> 30:00, '12740' -> 1:27:40, '45' -> 0:45."""
    d = re.sub(r"\D", "", digits or "")
    if not d:
        return 0
    d = d[-6:].rjust(6, "0")
    return int(d[:2]) * 3600 + int(d[2:4]) * 60 + int(d[4:])


def parse_time_input(text: str) -> Optional[int]:
    """Accepts '3000', '-3000', '1:27:40', '27:40', '30 min', '1h 20m', '90s'. Returns seconds."""
    t = (text or "").strip().lower().lstrip("-").strip()
    if not t:
        return None
    if re.fullmatch(r"\d+", t):
        return digits_to_seconds(t)
    if ":" in t:
        parts = t.split(":")
        if not all(p.strip().isdigit() for p in parts) or len(parts) > 3:
            return None
        secs = 0
        for p in parts:
            secs = secs * 60 + int(p)
        return secs
    m = re.fullmatch(r"(?:(\d+)\s*h(?:ours?|rs?)?)?\s*(?:(\d+)\s*m(?:in(?:ute)?s?)?)?\s*(?:(\d+)\s*s(?:ec(?:ond)?s?)?)?", t)
    if m and any(m.groups()):
        h, mi, s = (int(g) if g else 0 for g in m.groups())
        return h * 3600 + mi * 60 + s
    return None


def compute_window(center_sec: float, window_min: int, upper_bound: Optional[float]) -> (float, float):
    """Window of `window_min` minutes around `center_sec`, clamped to [0, upper_bound]."""
    span = window_min * 60.0
    start = max(0.0, center_sec - span / 2)
    end = start + span
    if upper_bound is not None and end > upper_bound:
        end = upper_bound
        start = max(0.0, end - span)
    return start, end


def fmt_hms(sec: float) -> str:
    sec = max(0, int(round(sec)))
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


# --------------------------------------------------------------------------------------
# FFmpeg filtergraph (9:16, 1080x1920)
# --------------------------------------------------------------------------------------
def _ff_path(p: str) -> str:
    """Escape a filesystem path for use inside a single-quoted filter option."""
    return p.replace("\\", "/").replace(":", "\\:").replace("'", "\\'")


_FONT_CANDIDATES: Dict[str, List[str]] = {
    "bold": ["DejaVuSans-Bold.ttf", "Arial Bold.ttf", "arialbd.ttf", "Roboto-Bold.ttf", "Inter-Bold.ttf"],
    "narrow": ["DejaVuSansCondensed-Bold.ttf", "ArialNarrow-Bold.ttf", "Roboto-Condensed-Bold.ttf"],
    "grotesk": ["Inter-SemiBold.ttf", "Inter-Bold.ttf", "SpaceGrotesk-Bold.ttf", "Helvetica.ttc", "DejaVuSans-Bold.ttf"],
    "mono": ["DejaVuSansMono-Bold.ttf", "Courier New Bold.ttf", "cour.ttf", "RobotoMono-Bold.ttf"],
}
_FONT_DIRS = ["/usr/share/fonts", "/usr/local/share/fonts", "/Library/Fonts", "/System/Library/Fonts",
              "C:/Windows/Fonts", os.path.expanduser("~/.fonts"), os.path.expanduser("~/.local/share/fonts")]


def find_font(name: str) -> Optional[str]:
    env = os.getenv(f"CLIPME_FONT_{name.upper()}")
    if env and os.path.exists(env):
        return env
    wanted = [c.lower() for c in _FONT_CANDIDATES.get(name, _FONT_CANDIDATES["bold"])]
    for base in _FONT_DIRS:
        for root, _dirs, files in os.walk(base):
            low = {f.lower(): f for f in files}
            for w in wanted:
                if w in low:
                    return os.path.join(root, low[w])
    return None


def _alpha_expr(w: int, h: int, r: int) -> str:
    """Anti-aliased rounded-rect / circle alpha for geq (r == w/2 == h/2 gives a circle)."""
    return (f"255*clip({r}-hypot(max(0,abs(X-{w / 2 - 0.5:.1f})-{w / 2 - r:.1f}),"
            f"max(0,abs(Y-{h / 2 - 0.5:.1f})-{h / 2 - r:.1f}))+0.5,0,1)")


def hook_filter(text: str, position: str, style: str, font: str, workdir: str) -> str:
    """drawtext filter that shows `text` for the first 3 seconds."""
    text = (text or "").strip()
    if not text:
        return ""
    lines = textwrap.wrap(text, width=22)[:3]
    path = os.path.join(workdir, "hook.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    y = {"top": "230", "middle": "(h-text_h)/2", "bottom": "h-text_h-520"}.get(position, "230")
    ff = find_font(font)
    parts = [f"fontfile='{_ff_path(ff)}'"] if ff else []
    parts += [f"textfile='{_ff_path(path)}'", "fontsize=68", "fontcolor=white", "line_spacing=12",
              "x=(w-text_w)/2", f"y={y}", "enable='between(t,0,3)'"]
    if style == "box":
        parts += ["box=1", "boxcolor=black@0.7", "boxborderw=26"]
    elif style == "clean":
        parts += ["shadowcolor=black@0.7", "shadowx=3", "shadowy=3"]
    else:
        parts += ["borderw=7", "bordercolor=black"]
    return "drawtext=" + ":".join(parts)


def build_filtergraph(spec: LayoutSpec, fps: int = 30, duration: float = 30.0, src_aspect: float = 16 / 9,
                      hook: str = "", captions_ass: Optional[str] = None) -> str:
    """Compile a layout into a filter_complex string whose output pad is [outv]."""
    plan: Plan = layout_plan(spec, src_aspect)
    nsrc = len(plan.tiles) + (1 if plan.bg == "blur" else 0)
    pads = "".join(f"[s{i}]" for i in range(nsrc))
    chains = [f"[0:v]fps={fps},setsar=1,split={nsrc}{pads}"]

    if plan.bg == "blur":
        chains.append(
            f"[s{nsrc - 1}]scale=540:960:force_original_aspect_ratio=increase,crop=540:960,"
            f"boxblur=14:3,scale={W}:{H}:flags=bicubic,eq=brightness=-0.08:saturation=1.1[bg]")
    else:
        chains.append(f"color=c=black:s={W}x{H}:r={fps}:d={max(duration, 0.1):.3f}[bg]")

    prev = "bg"
    for i, t in enumerate(plan.tiles):
        s = t.src
        chain = (f"[s{i}]crop=w='trunc(iw*{s.w:.6f}/2)*2':h='trunc(ih*{s.h:.6f}/2)*2':"
                 f"x='trunc(iw*{s.x:.6f})':y='trunc(ih*{s.y:.6f})',"
                 f"scale={t.w}:{t.h}:force_original_aspect_ratio=increase,crop={t.w}:{t.h},setsar=1")
        if t.shape != "rect":
            # The rounded/circle mask is computed ONCE (1-frame source) and looped, instead of
            # running geq on every video frame - ~10x faster for big cards.
            chains.append(f"{chain},format=yuv420p[r{i}]")
            chains.append(
                f"color=c=black:s={t.w}x{t.h}:r=1:d=1,format=gray,geq=lum='{_alpha_expr(t.w, t.h, t.radius)}',"
                f"loop=loop=-1:size=1:start=0,fps={fps},trim=duration={max(duration, 0.1):.3f},"
                f"setpts=PTS-STARTPTS[m{i}]")
            chains.append(f"[r{i}][m{i}]alphamerge[t{i}]")
        else:
            chains.append(f"{chain}[t{i}]")
        chains.append(f"[{prev}][t{i}]overlay=x={t.x}:y={t.y}:format=auto[c{i}]")
        prev = f"c{i}"

    tail = [x for x in (hook, f"ass='{_ff_path(captions_ass)}'" if captions_ass else "") if x]
    tail.append("format=yuv420p")
    chains.append(f"[{prev}]" + ",".join(tail) + "[outv]")
    return ";".join(chains)


def build_ffmpeg_filtergraph(req: ClipRequest, src_w: int = 1920, src_h: int = 1080) -> str:
    """Back-compat wrapper (v3 API)."""
    return build_filtergraph(req.spec(), req.fps, req.duration, src_w / src_h, req.hook_text)


QUALITY = {"fast": ("veryfast", 24), "balanced": ("faster", 20), "high": ("slow", 17)}


def build_ffmpeg_command(src: str, out: str, graph: str, req: ClipRequest, has_audio: bool = True) -> List[str]:
    preset, crf = QUALITY.get(req.quality, QUALITY["balanced"])
    venc = os.getenv("CLIPME_VENC", "libx264")
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-progress", "pipe:1", "-nostats",
           "-i", src, "-filter_complex", graph, "-map", "[outv]"]
    if has_audio:
        cmd += ["-map", "0:a:0?"]
    cmd += ["-t", f"{req.duration:.3f}", "-r", str(req.fps)]
    if venc == "libx264":
        cmd += ["-c:v", "libx264", "-preset", preset, "-crf", str(crf)]
    else:  # hardware encoders (h264_nvenc, h264_videotoolbox, h264_vaapi ...)
        cmd += ["-c:v", venc, "-b:v", "8M" if req.quality != "high" else "14M"]
    cmd += ["-pix_fmt", "yuv420p", "-profile:v", "high", "-c:a", "aac", "-b:a", "160k", "-ar", "48000",
            "-movflags", "+faststart", out]
    return cmd


# --------------------------------------------------------------------------------------
# Captions (word-by-word ASS)
# --------------------------------------------------------------------------------------
def _ass_time(t: float) -> str:
    cs = int(round(t * 100))
    h, rem = divmod(cs, 360000)
    m, rem = divmod(rem, 6000)
    s, c = divmod(rem, 100)
    return f"{h}:{m:02d}:{s:02d}.{c:02d}"


_ASS_FONTS = {"bold": "DejaVu Sans", "narrow": "DejaVu Sans Condensed", "grotesk": "Inter", "mono": "DejaVu Sans Mono"}


def words_to_ass(words: List[dict], style: str = "bold", font: str = "bold", group: int = 3) -> str:
    """words: [{'word': str, 'start': float, 'end': float}] -> ASS with the active word in lime."""
    border = 3 if style == "box" else 1
    outline, shadow = (14, 0) if style == "box" else ((3, 2) if style == "clean" else (7, 0))
    back = "&H99000000" if style == "box" else "&H00000000"
    header = (
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 1080\nPlayResY: 1920\nWrapStyle: 2\n\n"
        "[V4+ Styles]\nFormat: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,"
        "Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,"
        "MarginL,MarginR,MarginV,Encoding\n"
        f"Style: Cap,{_ASS_FONTS.get(font, 'DejaVu Sans')},78,&H00FFFFFF,&H00FFFFFF,&H00000000,{back},"
        f"-1,0,0,0,100,100,0,0,{border},{outline},{shadow},2,60,60,430,1\n\n"
        "[Events]\nFormat: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n")
    events = []
    clean = [w for w in words if str(w.get("word", "")).strip()]
    for g0 in range(0, len(clean), group):
        chunk = clean[g0:g0 + group]
        for i, w in enumerate(chunk):
            start = float(w["start"])
            end = float(chunk[i + 1]["start"]) if i + 1 < len(chunk) else float(w["end"])
            if end <= start:
                end = start + 0.2
            text = " ".join(
                (r"{\c&H12FFA3&}" + c["word"].strip().upper() + r"{\c&HFFFFFF&}") if j == i else c["word"].strip().upper()
                for j, c in enumerate(chunk))
            events.append(f"Dialogue: 0,{_ass_time(start)},{_ass_time(end)},Cap,,0,0,0,,{text}")
    return header + "\n".join(events) + "\n"


def transcribe_words(path: str, language: str = "en") -> List[dict]:
    """Word timestamps via faster-whisper (optional dependency). Returns [] if unavailable."""
    try:
        from faster_whisper import WhisperModel  # type: ignore
    except Exception:
        return []
    model = WhisperModel(os.getenv("CLIPME_WHISPER", "base"), compute_type="int8")
    segs, _info = model.transcribe(path, language=language, word_timestamps=True, vad_filter=True)
    return [{"word": w.word, "start": w.start, "end": w.end} for s in segs for w in (s.words or [])]


# --------------------------------------------------------------------------------------
# Gemini auto-clipper
# --------------------------------------------------------------------------------------
_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.S | re.I)


def parse_gemini_clip_suggestions(raw_text: str, offset: float = 0.0) -> List[dict]:
    """Defensive parse: tolerates markdown fences, prose around the JSON, {'clips': [...]} wrappers,
    string numbers and out-of-range scores. Never raises."""
    if not isinstance(raw_text, str) or not raw_text.strip():
        return []
    text = raw_text.strip()
    m = _FENCE.search(text)
    if m:
        text = m.group(1).strip()
    data = None
    for candidate in (text, text[text.find("["): text.rfind("]") + 1] if "[" in text else "",
                      text[text.find("{"): text.rfind("}") + 1] if "{" in text else ""):
        if not candidate:
            continue
        try:
            data = json.loads(candidate)
            break
        except (json.JSONDecodeError, ValueError):
            continue
    if isinstance(data, dict):
        data = data.get("clips") or data.get("moments") or next((v for v in data.values() if isinstance(v, list)), [])
    if not isinstance(data, list):
        return []
    out = []
    for item in data:
        if not isinstance(item, dict):
            continue
        try:
            start = float(item.get("start_sec", item.get("start", 0))) + offset
            end = float(item.get("end_sec", item.get("end", 0))) + offset
            score = int(float(item.get("viral_score", item.get("score", 80))))
        except (TypeError, ValueError):
            continue
        if end > start >= 0:
            out.append({"start_sec": start, "end_sec": end,
                        "title": str(item.get("title", "Viral moment"))[:80],
                        "reason": str(item.get("reason", "High engagement moment"))[:200],
                        "viral_score": max(0, min(100, score))})
    return out


def fit_suggestion_length(c: dict, lo: float = 15.0, hi: float = 60.0) -> dict:
    d = c["end_sec"] - c["start_sec"]
    if d < lo:
        c = {**c, "end_sec": c["start_sec"] + lo}
    elif d > hi:
        c = {**c, "end_sec": c["start_sec"] + hi}
    return c


GEMINI_PROMPT = (
    "You are a short-form video editor. Analyze this video and return ONLY a JSON array of up to {n} of the "
    "best moments for vertical 9:16 Instagram Reels / YouTube Shorts (each {lo}-{hi} seconds). Prefer: a "
    "strong hook in the first 3 seconds, big reactions, laughter or rage peaks, chaos, clutch plays, and for GTA / "
    "GTA RP: police chases, heist payoffs, comedic chaos, clutch escapes. Timestamps are seconds from the START "
    'of the provided video. Each item: {{"start_sec": number, "end_sec": number, "title": string, '
    '"reason": string, "viral_score": integer 0-100}}.{ctx}')

_GEMINI_SCHEMA = {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
    "start_sec": {"type": "NUMBER"}, "end_sec": {"type": "NUMBER"}, "title": {"type": "STRING"},
    "reason": {"type": "STRING"}, "viral_score": {"type": "INTEGER"}},
    "required": ["start_sec", "end_sec", "title", "reason", "viral_score"]}}


def gemini_auto_clip(source: str, api_key: Optional[str] = None, model: str = "gemini-2.5-flash",
                     max_clips: int = 5, offset: float = 0.0, context: str = "",
                     timeout: float = 90.0, _opener: Callable = urllib.request.urlopen) -> dict:
    """source = a local video file (sent inline, <= ~18 MB) or a public YouTube URL.
    Returns {'ok': bool, 'clips': [...], 'error': str|None}. Never raises."""
    key = (api_key or os.getenv("GEMINI_API_KEY", "")).strip()
    if not key:
        return {"ok": False, "clips": [], "error": "no_api_key"}
    if not source:
        return {"ok": False, "clips": [], "error": "no_source"}
    try:
        if os.path.isfile(source):
            if os.path.getsize(source) > 18 * 1024 * 1024:
                return {"ok": False, "clips": [], "error": "video_too_large_for_inline"}
            with open(source, "rb") as f:
                media = {"inlineData": {"mimeType": "video/mp4", "data": base64.b64encode(f.read()).decode()}}
        else:
            media = {"fileData": {"fileUri": source}}
        prompt = GEMINI_PROMPT.format(n=max_clips, lo=15, hi=60, ctx=(" Context: " + context) if context else "")
        payload = {"contents": [{"parts": [media, {"text": prompt}]}],
                   "generationConfig": {"responseMimeType": "application/json", "responseSchema": _GEMINI_SCHEMA,
                                        "temperature": 0.4}}
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        body = json.dumps(payload).encode()
        last_err = "unknown"
        for attempt in range(3):
            req = urllib.request.Request(url, data=body, method="POST",
                                         headers={"Content-Type": "application/json", "x-goog-api-key": key})
            try:
                with _opener(req, timeout=timeout) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as e:
                last_err = f"http_{e.code}"
                if e.code in (429, 500, 502, 503, 504) and attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                return {"ok": False, "clips": [], "error": last_err}
            except (urllib.error.URLError, TimeoutError, OSError):
                last_err = "network_or_timeout"
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                return {"ok": False, "clips": [], "error": last_err}
        cands = data.get("candidates") or []
        if not cands:
            return {"ok": False, "clips": [], "error": "empty_response"}
        parts = (cands[0].get("content") or {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts if isinstance(p, dict))
        clips = [fit_suggestion_length(c) for c in parse_gemini_clip_suggestions(text, offset)]
        clips = [score_candidate(c) for c in clips]
        clips.sort(key=lambda c: -c["viral_score"])
        return {"ok": True, "clips": clips[:max_clips], "error": None}
    except Exception as e:  # noqa: BLE001 - this function must never crash the caller
        return {"ok": False, "clips": [], "error": f"unexpected:{type(e).__name__}"}


# --------------------------------------------------------------------------------------
# Chat-hype spikes + viral heuristic "skills"
# --------------------------------------------------------------------------------------
@dataclass
class ChatBucket:
    timestamp_sec: float
    messages_count: int
    concurrent_viewers: int
    sample_messages: List[str] = field(default_factory=list)


GTA_WORDS = {"police", "cops", "chase", "heist", "clutch", "escape", "crash", "wasted", "busted", "gta", "helicopter",
             "bank", "getaway", "rp", "arrest", "shootout", "explosion", "no way", "omg", "rip", "w", "lmao", "lol"}
HOOK_WORDS = {"wait", "no way", "what", "omg", "insane", "crazy", "clutch", "bro", "yo", "wtf", "bruh"}


def score_candidate(c: dict, text: str = "") -> dict:
    """YouTube Shorts / Reels retention + GTA skill: adjusts viral_score using length sweet-spot,
    hook words, and GTA keywords found in `text` (chat/transcript) or the title/reason."""
    blob = f"{text} {c.get('title', '')} {c.get('reason', '')}".lower()
    d = c["end_sec"] - c["start_sec"]
    score = float(c.get("viral_score", 60))
    if 20 <= d <= 45:
        score += 4                       # sweet spot for completion rate / replays
    elif d > 75:
        score -= 6
    score += min(8, 2 * sum(1 for w in HOOK_WORDS if w in blob))
    gta_hits = sum(1 for w in GTA_WORDS if re.search(rf"\b{re.escape(w)}\b", blob))
    score += min(10, 2 * gta_hits)
    return {**c, "viral_score": int(max(0, min(100, round(score)))), "gta_hits": gta_hits}


def detect_chat_hype_spikes(buckets: List[ChatBucket], threshold_pct: float = 5.0,
                            pre_buffer_sec: float = 12.0, post_buffer_sec: float = 25.0,
                            cooldown_sec: float = 40.0) -> List[dict]:
    """A bucket spikes when messages >= threshold_pct % of concurrent viewers (400 chatters on 8,000 viewers = 5 %).
    Each spike -> clip [t - pre, t + post]. pre is clamped to 10-15 s, post to 20-30 s. Spikes closer than
    cooldown_sec are merged into one (the stronger) so the queue isn't flooded."""
    pre = min(15.0, max(10.0, pre_buffer_sec))
    post = min(30.0, max(20.0, post_buffer_sec))
    found = []
    for b in sorted(buckets, key=lambda x: x.timestamp_sec):
        if b.concurrent_viewers <= 0:
            continue
        pct = b.messages_count / b.concurrent_viewers * 100.0
        if pct < threshold_pct:
            continue
        clip = {"trigger_sec": b.timestamp_sec, "start_sec": max(0.0, b.timestamp_sec - pre),
                "end_sec": b.timestamp_sec + post, "engagement_pct": round(pct, 2),
                "title": "Chat went wild", "reason": f"{b.messages_count} chatters ({pct:.1f}% of viewers)",
                "viral_score": min(100, int(55 + pct * 4))}
        found.append(score_candidate(clip, " ".join(b.sample_messages)))
    merged: List[dict] = []
    for c in found:
        if merged and c["trigger_sec"] - merged[-1]["trigger_sec"] < cooldown_sec:
            if c["viral_score"] > merged[-1]["viral_score"]:
                merged[-1] = c
        else:
            merged.append(c)
    return merged


def parse_youtube_live_chat(lines: List[str], bucket_sec: float = 10.0, viewers: int = 0) -> List[ChatBucket]:
    """Parses yt-dlp's `.live_chat.json` (one JSON object per line) into message-count buckets."""
    counts: Dict[int, List[str]] = {}
    for ln in lines:
        try:
            obj = json.loads(ln)
            act = obj["replayChatItemAction"]
            off = float(act.get("videoOffsetTimeMsec", 0)) / 1000.0
            msg = ""
            for a in act.get("actions", []):
                item = (a.get("addChatItemAction") or {}).get("item", {})
                r = item.get("liveChatTextMessageRenderer")
                if r:
                    msg = "".join(run.get("text", "") for run in r.get("message", {}).get("runs", []))
            if msg and off >= 0:
                counts.setdefault(int(off // bucket_sec), []).append(msg)
        except (ValueError, KeyError, TypeError, AttributeError):
            continue
    return [ChatBucket(k * bucket_sec + bucket_sec / 2, len(v), viewers, v[:25]) for k, v in sorted(counts.items())]


# --------------------------------------------------------------------------------------
# Rendering pipeline (used by the background worker)
# --------------------------------------------------------------------------------------
def probe_video(path: str) -> dict:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,width,height",
                          "-of", "json", path], capture_output=True, text=True, timeout=60)
    info = json.loads(out.stdout or "{}")
    v = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), {})
    return {"width": int(v.get("width", 1920)), "height": int(v.get("height", 1080)),
            "has_audio": any(s.get("codec_type") == "audio" for s in info.get("streams", []))}


def render_local(src: str, out: str, req: ClipRequest, on_progress: Callable[[float], None] = lambda p: None,
                 should_cancel: Callable[[], bool] = lambda: False, log: Callable[[str], None] = lambda s: None,
                 spawn: Optional[Callable[[List[str]], subprocess.Popen]] = None) -> None:
    """Render an already-downloaded source file into a 1080x1920 clip."""
    info = probe_video(src)
    src_aspect = info["width"] / max(1, info["height"])
    work = tempfile.mkdtemp(prefix="clipme_")
    try:
        ass = None
        if req.auto_captions:
            words = transcribe_words(src)
            if words:
                ass = os.path.join(work, "cap.ass")
                with open(ass, "w", encoding="utf-8") as f:
                    f.write(words_to_ass(words, req.caption_style, req.font))
            else:
                log("Captions skipped: speech model not installed (pip install faster-whisper).")
        hook = hook_filter(req.hook_text, req.hook_position, req.caption_style, req.font, work)
        graph = build_filtergraph(req.spec(), req.fps, req.duration, src_aspect, hook, ass)
        cmd = build_ffmpeg_command(src, out, graph, req, info["has_audio"])
        log("ffmpeg " + " ".join(cmd[1:8]) + " ...")
        proc = spawn(cmd) if spawn else subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        total_us = req.duration * 1e6
        for line in proc.stdout:  # type: ignore[union-attr]
            if should_cancel():
                proc.kill()
                raise RuntimeError("cancelled")
            if line.startswith("out_time_us="):
                try:
                    on_progress(min(1.0, int(line.split("=")[1]) / total_us))
                except ValueError:
                    pass
        err = proc.stderr.read() if proc.stderr else ""  # type: ignore[union-attr]
        code = proc.wait()
        for pipe in (proc.stdout, proc.stderr):
            if pipe:
                pipe.close()
        if code != 0:
            raise RuntimeError("ffmpeg failed: " + err[-600:])
        on_progress(1.0)
    finally:
        shutil.rmtree(work, ignore_errors=True)
