"""Tiny in-process job manager: background threads, live progress, cancel, log, push notify."""
from __future__ import annotations

import json
import subprocess
import threading
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class Cancelled(Exception):
    pass


@dataclass
class Job:
    id: str
    kind: str
    steps: List[str] = field(default_factory=list)
    status: str = "queued"          # queued | running | done | error | cancelled
    step: int = 0                   # index into steps
    progress: float = 0.0           # 0..1 for the whole job
    step_progress: float = 0.0      # 0..1 for the current step
    message: str = ""
    detail: str = ""                # e.g. "Downloading, live pieces 5/93"
    result: Any = None
    error: Optional[str] = None
    log: List[str] = field(default_factory=list)
    created: float = field(default_factory=time.time)
    started: Optional[float] = None
    finished: Optional[float] = None
    push_token: Optional[str] = None
    cancel_flag: threading.Event = field(default_factory=threading.Event, repr=False)
    procs: List[subprocess.Popen] = field(default_factory=list, repr=False)

    # --- helpers used by workers -------------------------------------------------------
    def check(self) -> None:
        if self.cancel_flag.is_set():
            raise Cancelled()

    def add_log(self, line: str) -> None:
        self.log.append(f"[{time.strftime('%H:%M:%S')}] {line}")
        del self.log[:-400]

    def set_step(self, i: int, message: str = "", detail: str = "") -> None:
        self.step, self.step_progress = i, 0.0
        if message:
            self.message = message
        self.detail = detail
        self._recalc()

    def set_progress(self, p: float, detail: Optional[str] = None) -> None:
        self.step_progress = max(0.0, min(1.0, p))
        if detail is not None:
            self.detail = detail
        self._recalc()

    def _recalc(self) -> None:
        n = max(1, len(self.steps))
        self.progress = min(1.0, (self.step + self.step_progress) / n)

    def spawn(self, cmd: List[str], **kw) -> subprocess.Popen:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **kw)
        self.procs.append(p)
        return p

    def to_dict(self) -> dict:
        now = time.time()
        return {"id": self.id, "kind": self.kind, "status": self.status, "steps": self.steps, "step": self.step,
                "progress": round(self.progress, 4), "step_progress": round(self.step_progress, 4),
                "message": self.message, "detail": self.detail, "result": self.result, "error": self.error,
                "elapsed": round((self.finished or now) - (self.started or self.created), 1)}


class JobManager:
    def __init__(self, workers: int = 2, notifier: Optional[Callable[[Job], None]] = None):
        self.jobs: Dict[str, Job] = {}
        self.pool = ThreadPoolExecutor(max_workers=workers)
        self.notifier = notifier or send_expo_push
        self.lock = threading.Lock()

    def submit(self, kind: str, steps: List[str], fn: Callable[[Job], Any], push_token: Optional[str] = None) -> Job:
        job = Job(id=uuid.uuid4().hex[:12], kind=kind, steps=steps, push_token=push_token)
        with self.lock:
            self.jobs[job.id] = job
            for jid in [k for k, j in self.jobs.items() if j.finished and time.time() - j.finished > 6 * 3600]:
                self.jobs.pop(jid, None)

        def run() -> None:
            if job.cancel_flag.is_set():
                job.status = "cancelled"
                return
            job.status, job.started = "running", time.time()
            try:
                job.result = fn(job)
                job.status, job.progress = "done", 1.0
            except Cancelled:
                job.status, job.message = "cancelled", "Cancelled"
            except Exception as e:  # noqa: BLE001
                job.status, job.error = "error", str(e) or type(e).__name__
                job.add_log(f"ERROR: {job.error}")
            finally:
                job.finished = time.time()
                if job.status in ("done", "error") and job.push_token and job.kind == "render":
                    try:
                        self.notifier(job)
                    except Exception:  # noqa: BLE001
                        pass

        self.pool.submit(run)
        return job

    def get(self, job_id: str) -> Optional[Job]:
        return self.jobs.get(job_id)

    def cancel(self, job_id: str) -> bool:
        job = self.jobs.get(job_id)
        if not job:
            return False
        job.cancel_flag.set()
        for p in job.procs:
            try:
                if p.poll() is None:
                    p.kill()
            except Exception:  # noqa: BLE001
                pass
        return True


def send_expo_push(job: Job) -> None:
    """Best-effort Expo push so the phone is told when clips are ready while the app is closed."""
    if not job.push_token or not job.push_token.startswith(("ExponentPushToken", "ExpoPushToken")):
        return
    ok = job.status == "done"
    n = len((job.result or {}).get("clips", [])) if ok else 0
    body = {"to": job.push_token, "title": "Clips ready" if ok else "Clip failed",
            "body": f"{n} clip{'s' if n != 1 else ''} saved - tap to open" if ok else (job.error or "Something went wrong"),
            "data": {"job_id": job.id}, "sound": "default"}
    req = urllib.request.Request("https://exp.host/--/api/v2/push/send", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=10).read()
