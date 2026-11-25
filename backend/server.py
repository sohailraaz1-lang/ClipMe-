"""ClipMe API server.   Run:  uvicorn server:app --host 0.0.0.0 --port 8000   (from the backend/ folder)

Requires fastapi>=0.115.6 (Starlette >= 0.40 serves HTTP Range requests, which the phone's video player needs for seeking).
"""
from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException, Header, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import autobox
import clip_engine as eng
import sources
from jobs import JobManager, Job
from layouts import PRESETS, SPLIT_RATIOS, Box

MEDIA = os.path.abspath(os.getenv("CLIPME_MEDIA_DIR", "./media"))
for d in ("previews", "outputs", "frames", "work", "thumbs"):
    os.makedirs(os.path.join(MEDIA, d), exist_ok=True)

app = FastAPI(title="ClipMe Reels & Shorts Backend", version="2.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.mount("/media", StaticFiles(directory=MEDIA), name="media")
jobs = JobManager(workers=int(os.getenv("CLIPME_WORKERS", "2")))
windows: Dict[str, dict] = {}


# --------------------------------------------------------------------------------------
# Schemas
# --------------------------------------------------------------------------------------
class BoxIn(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    w: float = Field(gt=0, le=1)
    h: float = Field(gt=0, le=1)

    def box(self) -> Box:
        return Box(self.x, self.y, self.w, self.h)


class UrlIn(BaseModel):
    url: str


class WindowIn(BaseModel):
    url: str
    start: float = Field(ge=0)
    end: float = Field(gt=0)
    is_live: bool = False


class ClipSpan(BaseModel):
    start: float
    end: float


class LayoutIn(BaseModel):
    preset: str = "split"
    split_ratio: str = "50/50"
    cam_corner: str = "br"
    cam_size: str = "M"
    game_box: Optional[BoxIn] = None
    cam_box: Optional[BoxIn] = None


class RenderIn(BaseModel):
    url: str
    is_live: bool = False
    clips: List[ClipSpan]
    layout: LayoutIn = LayoutIn()
    captions: bool = False
    caption_style: str = "bold"
    font: str = "bold"
    hook_text: str = ""
    hook_position: str = "top"
    quality: str = "balanced"
    fps: int = 30
    push_token: Optional[str] = None


class GeminiIn(BaseModel):
    window_id: Optional[str] = None
    url: Optional[str] = None
    api_key: Optional[str] = None
    max_clips: int = 5
    game_hint: str = "gta"


class ChatBucketIn(BaseModel):
    t: float
    messages: int
    viewers: int
    samples: List[str] = []


class HypeIn(BaseModel):
    buckets: List[ChatBucketIn]
    threshold_pct: float = 5.0
    pre_sec: float = 12.0
    post_sec: float = 25.0


class HypeScanIn(BaseModel):
    url: str
    start: float = 0
    end: float = 0
    viewers: int = 0
    threshold_pct: float = 5.0


# --------------------------------------------------------------------------------------
@app.get("/api/health")
def health():
    return {"ok": True, "version": app.version, "presets": PRESETS, "split_ratios": list(SPLIT_RATIOS),
            "gemini_env": bool(os.getenv("GEMINI_API_KEY")), "captions": _has_whisper()}


def _has_whisper() -> bool:
    try:
        import faster_whisper  # noqa: F401
        return True
    except Exception:
        return False


@app.get("/api/trending")
def trending(platform: str = "all", game: str = "", lang: str = ""):
    return sources.trending(platform, game, lang)


# ---- 1. check link + first frame ------------------------------------------------------
@app.post("/api/resolve")
def resolve(body: UrlIn):
    if not sources.detect_platform(body.url):
        raise HTTPException(400, "Paste a YouTube or Twitch link.")
    job = jobs.submit("resolve", ["Checking the link...", "Getting a frame from the stream..."],
                      lambda j: sources.resolve_stream(j, body.url, MEDIA))
    return {"job_id": job.id}


# ---- 2. load a window (low-quality preview) --------------------------------------------
@app.post("/api/window")
def load_window(body: WindowIn):
    if body.end - body.start > 15 * 60 or body.end <= body.start:
        raise HTTPException(400, "Window must be between 0 and 15 minutes.")
    wid = uuid.uuid4().hex[:10]

    def work(job: Job):
        job.set_step(0, "Loading this part...", "")
        raw = sources.download_section(job, body.url, body.start, body.end, os.path.join(MEDIA, "work"), f"win_{wid}",
                                       is_live=body.is_live, max_height=360, label="Loading")
        job.set_step(1, "Preparing preview...", "")
        prev = os.path.join(MEDIA, "previews", f"{wid}.mp4")
        strip = os.path.join(MEDIA, "previews", f"{wid}_strip.jpg")
        dur = sources.make_preview(job, raw, prev, strip)
        try:
            os.remove(raw)
        except OSError:
            pass
        windows[wid] = {"url": body.url, "start": body.start, "end": body.end, "path": prev, "duration": dur}
        return {"window_id": wid, "start": body.start, "end": body.end, "duration": dur,
                "preview_url": f"/media/previews/{wid}.mp4", "strip_url": f"/media/previews/{wid}_strip.jpg"}

    job = jobs.submit("window", ["Loading this part...", "Preparing preview..."], work)
    return {"job_id": job.id}


@app.get("/api/windows/{wid}/frame")
def window_frame(wid: str, t: float = 0.0):
    w = windows.get(wid)
    if not w:
        raise HTTPException(404, "Window expired - load this part again.")
    name = f"thumbs/{wid}_{int(t * 10)}.jpg"
    out = os.path.join(MEDIA, name)
    if not os.path.exists(out):
        try:
            sources.frame_from_file(w["path"], min(t, max(0.0, w["duration"] - 0.1)), out)
        except RuntimeError as e:
            raise HTTPException(500, str(e))
    return {"frame_url": "/media/" + name}


@app.get("/api/windows/{wid}/autobox")
def window_autobox(wid: str, t: float = 0.0):
    frame = window_frame(wid, t)
    return autobox.detect_boxes(os.path.join(MEDIA, frame["frame_url"][len("/media/"):]))


# ---- 3. render (background) ------------------------------------------------------------
def _fmt(sec: float) -> str:
    return eng.fmt_hms(sec)


@app.post("/api/render")
def render(body: RenderIn):
    if not body.clips:
        raise HTTPException(400, "Add at least one clip.")
    if len(body.clips) > eng.MAX_CLIPS:
        raise HTTPException(400, f"Up to {eng.MAX_CLIPS} clips at a time.")
    for c in body.clips:
        msg = eng.validate_clip_times(c.start, c.end)
        if msg:
            raise HTTPException(400, msg)
    try:
        preset = eng.normalize_preset(body.layout.preset)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if body.fps not in (30, 60):
        raise HTTPException(400, "fps must be 30 or 60.")
    stamp = time.strftime("%m%d_%H%M%S")
    n = len(body.clips)

    def work(job: Job):
        results = []
        for i, c in enumerate(body.clips):
            job.check()
            label = f"Clip {i + 1} of {n} \u00b7 {_fmt(c.start)}"
            job.set_step(i, label, "Starting...")
            req = eng.ClipRequest(
                url=body.url, start_sec=c.start, end_sec=c.end, preset=preset, split_ratio=body.layout.split_ratio,
                cam_corner=body.layout.cam_corner, cam_size=body.layout.cam_size,
                game_box=body.layout.game_box.box() if body.layout.game_box else Box(),
                cam_box=body.layout.cam_box.box() if body.layout.cam_box else None,
                auto_captions=body.captions, caption_style=body.caption_style, font=body.font,
                hook_text=body.hook_text, hook_position=body.hook_position, quality=body.quality, fps=body.fps,
                is_live=body.is_live)
            work_dir = os.path.join(MEDIA, "work")
            src = sources.download_section(
                job, body.url, c.start, c.end, work_dir, f"clip_{stamp}_{i}", is_live=body.is_live, max_height=1080,
                on_progress=lambda p, d: job.set_progress(p * 0.7, d))
            job.set_progress(0.7, "Rendering 1080x1920...")
            name = f"clip_{stamp}_{c.start:.0f}_{i + 1}.mp4"
            out = os.path.join(MEDIA, "outputs", name)
            eng.render_local(src, out, req, on_progress=lambda p: job.set_progress(0.7 + 0.3 * p, "Rendering 1080x1920..."),
                             should_cancel=job.cancel_flag.is_set, log=job.add_log, spawn=job.spawn)
            try:
                os.remove(src)
            except OSError:
                pass
            _thumb(name)
            results.append(_clip_info(name))
        return {"clips": results}

    job = jobs.submit("render", [f"Clip {i + 1}" for i in range(n)], work, push_token=body.push_token)
    return {"job_id": job.id}


# ---- jobs -----------------------------------------------------------------------------------
@app.get("/api/jobs/{job_id}")
def job_status(job_id: str):
    j = jobs.get(job_id)
    if not j:
        raise HTTPException(404, "Unknown job")
    return j.to_dict()


@app.get("/api/jobs/{job_id}/log")
def job_log(job_id: str):
    j = jobs.get(job_id)
    if not j:
        raise HTTPException(404, "Unknown job")
    return {"log": j.log}


@app.post("/api/jobs/{job_id}/cancel")
def job_cancel(job_id: str):
    return {"cancelled": jobs.cancel(job_id)}


# ---- clips library ----------------------------------------------------------------------------
def _thumb(name: str) -> None:
    out = os.path.join(MEDIA, "thumbs", name + ".jpg")
    if not os.path.exists(out):
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", "1", "-i", os.path.join(MEDIA, "outputs", name),
                        "-frames:v", "1", "-vf", "scale=270:-2", out], capture_output=True, timeout=60)


def _clip_info(name: str) -> dict:
    p = os.path.join(MEDIA, "outputs", name)
    d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p],
                       capture_output=True, text=True, timeout=30)
    try:
        dur = float(d.stdout.strip())
    except ValueError:
        dur = 0.0
    return {"name": name, "url": f"/media/outputs/{name}", "thumb_url": f"/media/thumbs/{name}.jpg",
            "size": os.path.getsize(p), "duration": dur, "created": os.path.getmtime(p)}


@app.get("/api/clips")
def list_clips():
    d = os.path.join(MEDIA, "outputs")
    names = sorted((f for f in os.listdir(d) if f.endswith(".mp4")), key=lambda f: -os.path.getmtime(os.path.join(d, f)))
    return {"clips": [_clip_info(n) for n in names]}


@app.delete("/api/clips/{name}")
def delete_clip(name: str):
    if "/" in name or ".." in name:
        raise HTTPException(400, "bad name")
    for p in (os.path.join(MEDIA, "outputs", name), os.path.join(MEDIA, "thumbs", name + ".jpg")):
        if os.path.exists(p):
            os.remove(p)
    return {"deleted": name}


# ---- smart clipping: Gemini + chat hype ----------------------------------------------------------
@app.post("/api/gemini-auto-clip")
def gemini_auto(body: GeminiIn, x_gemini_key: Optional[str] = Header(None)):
    key = body.api_key or x_gemini_key or os.getenv("GEMINI_API_KEY")
    if not key:
        raise HTTPException(400, "Add a Gemini API key in Settings (or set GEMINI_API_KEY on the server).")
    if body.window_id:
        w = windows.get(body.window_id)
        if not w:
            raise HTTPException(404, "Window expired - load this part again.")
        source, offset = w["path"], float(w["start"])
    elif body.url:
        source, offset = body.url, 0.0
    else:
        raise HTTPException(400, "Provide window_id or url.")
    ctx = "The game is GTA / GTA RP." if body.game_hint == "gta" else ""

    def work(job: Job):
        job.set_step(0, "Finding the best moments...", "Asking Gemini")
        res = eng.gemini_auto_clip(source, key, max_clips=body.max_clips, offset=offset, context=ctx)
        if not res["ok"]:
            raise RuntimeError(_gemini_msg(res["error"]))
        return res

    job = jobs.submit("gemini", ["Finding the best moments..."], work)
    return {"job_id": job.id}


def _gemini_msg(code: Optional[str]) -> str:
    return {"no_api_key": "Add a Gemini API key.", "http_400": "Gemini rejected the request (check the key/model).",
            "http_403": "Gemini key not allowed.", "http_429": "Gemini rate limit - try again in a minute.",
            "network_or_timeout": "Couldn't reach Gemini (network/timeout).",
            "video_too_large_for_inline": "This window is too big to send. Use the 5 min window.",
            "empty_response": "Gemini returned nothing. Try again."}.get(code or "", f"Gemini failed ({code}).")


@app.post("/api/hype/detect")
def hype_detect(body: HypeIn):
    buckets = [eng.ChatBucket(b.t, b.messages, b.viewers, b.samples) for b in body.buckets]
    return {"clips": eng.detect_chat_hype_spikes(buckets, body.threshold_pct, body.pre_sec, body.post_sec)}


@app.post("/api/hype/scan")
def hype_scan(body: HypeScanIn):
    """Chat-replay scan for YouTube VODs (live chat replay JSON from yt-dlp)."""
    def work(job: Job):
        job.set_step(0, "Reading chat replay...", "")
        d = os.path.join(MEDIA, "work", "chat_" + uuid.uuid4().hex[:6])
        os.makedirs(d, exist_ok=True)
        cmd = sources._ytdlp() + ["--skip-download", "--write-subs", "--sub-langs", "live_chat", "--no-warnings",
                                  "-o", os.path.join(d, "c.%(ext)s"), body.url]
        r = sources._run(job, cmd, timeout=180)
        files = [f for f in os.listdir(d) if f.endswith(".live_chat.json")]
        if r.returncode != 0 or not files:
            raise RuntimeError("No chat replay available for this video.")
        with open(os.path.join(d, files[0]), encoding="utf-8") as f:
            buckets = eng.parse_youtube_live_chat(f.readlines(), viewers=body.viewers)
        if body.end > body.start:
            buckets = [b for b in buckets if body.start <= b.timestamp_sec <= body.end]
        if not any(b.concurrent_viewers for b in buckets):
            peak = max((b.messages_count for b in buckets), default=0)
            for b in buckets:   # viewers unknown: treat the busiest bucket as 5 % -> relative spikes
                b.concurrent_viewers = max(1, int(peak * 20))
        return {"clips": eng.detect_chat_hype_spikes(buckets, body.threshold_pct)}

    job = jobs.submit("hype", ["Reading chat replay..."], work)
    return {"job_id": job.id}
