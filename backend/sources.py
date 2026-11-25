"""YouTube / Twitch access: link check, partial-section download (no full-VOD downloads),
low-quality preview windows, frame grabs, and the Trending feed."""
from __future__ import annotations

import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from typing import Callable, List, Optional

from jobs import Cancelled, Job

YT_RE = re.compile(r"^https?://(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/", re.I)
TW_RE = re.compile(r"^https?://(www\.|m\.)?twitch\.tv/", re.I)


def detect_platform(url: str) -> Optional[str]:
    u = (url or "").strip()
    if YT_RE.match(u):
        return "youtube"
    if TW_RE.match(u):
        return "twitch"
    return None


def _ytdlp() -> List[str]:
    exe = shutil.which("yt-dlp")
    return [exe] if exe else [sys.executable, "-m", "yt_dlp"]


def _run(job: Optional[Job], cmd: List[str], timeout: float = 120.0) -> subprocess.CompletedProcess:
    if job:
        job.check()
    p = job.spawn(cmd) if job else subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        out, err = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        raise RuntimeError("Timed out talking to the stream. Try again.")
    if job:
        job.check()
    return subprocess.CompletedProcess(cmd, p.returncode, out, err)


def _friendly(err: str) -> str:
    e = (err or "").lower()
    if "private" in e or "sign in" in e:
        return "This video is private or needs a login."
    if "not a valid url" in e or "unsupported url" in e:
        return "That doesn't look like a YouTube or Twitch link."
    if "offline" in e or "is not currently live" in e:
        return "That Twitch channel is offline right now."
    if "this live event will begin" in e or "premiere" in e:
        return "That stream hasn't started yet."
    return "Couldn't read that link. Check it and try again."


# --------------------------------------------------------------------------------------
def resolve_stream(job: Job, url: str, media_dir: str) -> dict:
    """Step 1: check the link (metadata). Step 2: grab a frame so boxes can be placed."""
    url = url.strip()
    platform = detect_platform(url)
    if not platform:
        raise RuntimeError("Paste a YouTube or Twitch link.")
    job.set_step(0, "Checking the link...", "Reading the stream's details")
    r = _run(job, _ytdlp() + ["-J", "--no-playlist", "--no-warnings", url], timeout=90)
    if r.returncode != 0:
        job.add_log(r.stderr[-500:])
        raise RuntimeError(_friendly(r.stderr))
    info = json.loads(r.stdout)
    is_live = bool(info.get("is_live")) or info.get("live_status") == "is_live"
    now = time.time()
    start_ts = info.get("release_timestamp") or info.get("timestamp")
    duration = info.get("duration")
    live_edge = None
    if is_live:
        if start_ts:
            live_edge = max(0.0, now - float(start_ts))
        elif duration:
            live_edge = float(duration)
        else:
            raise RuntimeError("Couldn't read when this stream started.")
    elif not duration:
        raise RuntimeError("Couldn't read this video's length.")
    meta = {"url": url, "platform": platform, "title": info.get("title") or "Untitled",
            "channel": info.get("channel") or info.get("uploader") or "", "is_live": is_live,
            "duration": None if is_live else float(duration), "live_edge": live_edge, "resolved_at": now,
            "thumbnail": info.get("thumbnail"), "viewers": info.get("concurrent_view_count") or info.get("view_count")}
    job.add_log(f"resolved: live={is_live} edge={live_edge} dur={duration}")

    job.set_step(1, "Getting a frame from the stream...", "So you can place the game and cam boxes")
    frame_rel = None
    try:
        frame_rel = grab_frame(job, url, is_live, (duration or 0) / 2 if not is_live else None, media_dir)
    except Cancelled:
        raise
    except Exception as e:  # noqa: BLE001 - frame is nice-to-have; window preview can supply it later
        job.add_log(f"frame grab failed: {e}")
    meta["frame_url"] = frame_rel
    return meta


def grab_frame(job: Optional[Job], url: str, is_live: bool, at: Optional[float], media_dir: str) -> str:
    r = _run(job, _ytdlp() + ["-g", "-f", "best[height<=720]/best", "--no-playlist", url], timeout=60)
    direct = (r.stdout or "").strip().splitlines()
    if r.returncode != 0 or not direct:
        raise RuntimeError("no direct stream URL")
    os.makedirs(os.path.join(media_dir, "frames"), exist_ok=True)
    name = f"frames/f_{int(time.time() * 1000)}.jpg"
    cmd = ["ffmpeg", "-y", "-v", "error"]
    if at:
        cmd += ["-ss", f"{at:.2f}"]
    cmd += ["-i", direct[0], "-frames:v", "1", "-vf", "scale=960:-2", "-q:v", "3", os.path.join(media_dir, name)]
    r2 = _run(job, cmd, timeout=60)
    if r2.returncode != 0:
        raise RuntimeError("ffmpeg frame failed")
    return "/media/" + name


# --------------------------------------------------------------------------------------
_FRAG = re.compile(r"\(frag (\d+)/(\d+)\)")
_PCT = re.compile(r"\[download\]\s+([\d.]+)%")


def download_section(job: Job, url: str, start: float, end: float, out_dir: str, stem: str, *,
                     is_live: bool, max_height: int, label: str = "Downloading",
                     on_progress: Optional[Callable[[float, str], None]] = None) -> str:
    """Download ONLY [start, end] seconds (yt-dlp --download-sections) - never the whole multi-hour VOD."""
    os.makedirs(out_dir, exist_ok=True)
    for old in glob.glob(os.path.join(out_dir, stem + ".*")):
        os.remove(old)
    fmt = f"bv*[height<={max_height}]+ba/b[height<={max_height}]/b"
    cmd = _ytdlp() + ["--no-playlist", "--no-warnings", "--newline", "-f", fmt,
                      "--download-sections", f"*{start:.2f}-{end:.2f}", "--force-keyframes-at-cuts",
                      "--merge-output-format", "mp4", "--concurrent-fragments", "4",
                      "-o", os.path.join(out_dir, stem + ".%(ext)s")]
    if is_live:
        cmd.append("--live-from-start")
    cmd.append(url)
    job.add_log("yt-dlp " + " ".join(cmd[len(_ytdlp()):-1]))
    p = job.spawn(cmd)
    t0 = time.time()
    tail = []
    for line in p.stdout:  # type: ignore[union-attr]
        job.check()
        tail.append(line.strip())
        del tail[:-12]
        m = _FRAG.search(line)
        pct = _PCT.search(line)
        frac, detail = None, None
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            frac, detail = a / max(1, b), f"{label}, live pieces {a}/{b}" if is_live else f"{label}, piece {a}/{b}"
        elif pct:
            frac, detail = float(pct.group(1)) / 100.0, f"{label} {pct.group(1)}%"
        if frac is not None:
            detail += f"\n{int(time.time() - t0)}s elapsed"
            (on_progress or job.set_progress)(frac, detail)
    err = p.stderr.read() if p.stderr else ""  # type: ignore[union-attr]
    if p.wait() != 0:
        job.add_log("\n".join(tail) + "\n" + err[-400:])
        if is_live:
            raise RuntimeError("That part of the stream isn't available yet. Live streams keep up to ~5 days "
                               "on YouTube; Twitch only keeps saved broadcasts.")
        raise RuntimeError("Couldn't download that part. " + _friendly(err))
    files = sorted(glob.glob(os.path.join(out_dir, stem + ".*")), key=os.path.getmtime)
    files = [f for f in files if not f.endswith((".part", ".ytdl"))]
    if not files:
        raise RuntimeError("Download finished but no file was produced.")
    return files[-1]


def make_preview(job: Job, src: str, dst: str, strip_dst: str, n_thumbs: int = 12) -> float:
    """Re-encode to a small, seekable, faststart H.264 file and build a thumbnail strip for the timeline."""
    r = _run(job, ["ffmpeg", "-y", "-v", "error", "-i", src, "-vf", "scale=-2:360,fps=30", "-c:v", "libx264",
                   "-preset", "veryfast", "-crf", "30", "-g", "30", "-c:a", "aac", "-b:a", "64k",
                   "-movflags", "+faststart", dst], timeout=600)
    if r.returncode != 0:
        raise RuntimeError("Couldn't prepare the preview.")
    d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", dst],
                       capture_output=True, text=True, timeout=30)
    duration = float(d.stdout.strip() or 0)
    fps = n_thumbs / max(1.0, duration)
    _run(job, ["ffmpeg", "-y", "-v", "error", "-i", dst, "-vf", f"fps={fps:.5f},scale=120:-2,tile={n_thumbs}x1",
               "-frames:v", "1", "-q:v", "5", strip_dst], timeout=120)
    return duration


def frame_from_file(path: str, t: float, out_jpg: str) -> None:
    r = subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", f"{max(0.0, t):.2f}", "-i", path, "-frames:v", "1",
                        "-vf", "scale=960:-2", "-q:v", "3", out_jpg], capture_output=True, text=True, timeout=60)
    if r.returncode != 0 or not os.path.exists(out_jpg):
        raise RuntimeError("Couldn't grab that frame.")


# --------------------------------------------------------------------------------------
# Trending feed
# --------------------------------------------------------------------------------------
_cache: dict = {}


def _http_json(url: str, headers: Optional[dict] = None, data: Optional[bytes] = None, timeout: float = 12):
    req = urllib.request.Request(url, headers=headers or {}, data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _twitch(game: str, lang: str) -> List[dict]:
    cid, secret = os.getenv("TWITCH_CLIENT_ID"), os.getenv("TWITCH_CLIENT_SECRET")
    if not (cid and secret):
        return []
    tok = _http_json("https://id.twitch.tv/oauth2/token", data=urllib.parse.urlencode(
        {"client_id": cid, "client_secret": secret, "grant_type": "client_credentials"}).encode())["access_token"]
    h = {"Client-Id": cid, "Authorization": f"Bearer {tok}"}
    q = {"first": "25"}
    if lang:
        q["language"] = lang
    if game:
        g = _http_json("https://api.twitch.tv/helix/games?" + urllib.parse.urlencode({"name": game}), h).get("data")
        if not g:
            return []
        q["game_id"] = g[0]["id"]
    out = []
    for s in _http_json("https://api.twitch.tv/helix/streams?" + urllib.parse.urlencode(q), h).get("data", []):
        out.append({"id": "tw-" + s["id"], "platform": "twitch", "title": s["title"], "channel": s["user_name"],
                    "game": s.get("game_name", ""), "lang": (s.get("language") or "").upper(),
                    "viewers": s["viewer_count"], "url": f"https://www.twitch.tv/{s['user_login']}",
                    "thumbnail": s["thumbnail_url"].replace("{width}", "640").replace("{height}", "360")})
    return out


def _youtube(game: str, lang: str) -> List[dict]:
    key = os.getenv("YOUTUBE_API_KEY")
    if not key:
        return []
    q = {"part": "snippet", "type": "video", "eventType": "live", "maxResults": "25", "order": "viewCount",
         "videoCategoryId": "20", "key": key}
    if game:
        q["q"] = game
    if lang:
        q["relevanceLanguage"] = lang
    items = _http_json("https://www.googleapis.com/youtube/v3/search?" + urllib.parse.urlencode(q)).get("items", [])
    ids = ",".join(i["id"]["videoId"] for i in items)
    if not ids:
        return []
    det = _http_json("https://www.googleapis.com/youtube/v3/videos?" + urllib.parse.urlencode(
        {"part": "liveStreamingDetails,snippet", "id": ids, "key": key}))
    out = []
    for v in det.get("items", []):
        views = int(v.get("liveStreamingDetails", {}).get("concurrentViewers", 0) or 0)
        sn = v["snippet"]
        out.append({"id": "yt-" + v["id"], "platform": "youtube", "title": sn["title"], "channel": sn["channelTitle"],
                    "game": game or "", "lang": (sn.get("defaultAudioLanguage") or "")[:2].upper(), "viewers": views,
                    "url": f"https://www.youtube.com/watch?v={v['id']}",
                    "thumbnail": (sn["thumbnails"].get("high") or sn["thumbnails"]["default"])["url"]})
    return sorted(out, key=lambda x: -x["viewers"])


DEMO = [
    {"id": "demo-1", "platform": "youtube", "title": "GTA 6 RP - Heist night with chat", "channel": "DemoStreamer",
     "game": "Grand Theft Auto V", "lang": "EN", "viewers": 4800, "url": "https://www.youtube.com/watch?v=demo",
     "thumbnail": None},
    {"id": "demo-2", "platform": "twitch", "title": "Ranked grind - road to Radiant", "channel": "demo_valorant",
     "game": "VALORANT", "lang": "EN", "viewers": 9800, "url": "https://www.twitch.tv/demo", "thumbnail": None},
]


def trending(platform: str = "all", game: str = "", lang: str = "") -> dict:
    key = (platform, game, lang)
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < 60:
        return hit[1]
    items: List[dict] = []
    errors = []
    for name, fn in (("twitch", _twitch), ("youtube", _youtube)):
        if platform in ("all", name):
            try:
                items += fn(game, lang.lower())
            except Exception as e:  # noqa: BLE001
                errors.append(f"{name}: {type(e).__name__}")
    demo = not items and not errors and not (os.getenv("TWITCH_CLIENT_ID") or os.getenv("YOUTUBE_API_KEY"))
    if demo:
        items = [d for d in DEMO if platform in ("all", d["platform"])]
    items.sort(key=lambda x: -x["viewers"])
    res = {"items": items, "demo": demo, "errors": errors, "updated": time.time()}
    _cache[key] = (time.time(), res)
    return res
