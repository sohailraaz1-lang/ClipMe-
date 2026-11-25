"""Automatic facecam detection for the 'Facecam found and boxed for you' step.
Uses OpenCV's bundled Haar face detector when available; otherwise returns a corner guess with found=False."""
from __future__ import annotations

from typing import Optional

DEFAULT_CAM = {"x": 0.0, "y": 0.62, "w": 0.24, "h": 0.38}


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def box_from_face(fx: float, fy: float, fw: float, fh: float, img_w: int, img_h: int) -> dict:
    """Expand a detected face rectangle (pixels) to a typical webcam rectangle and snap it to the nearest
    screen edge/corner when the face sits near it (webcams are almost always docked to a corner)."""
    cw, ch = fw * 2.6, fh * 2.4
    cx, cy = fx + fw / 2, fy + fh / 2
    x0, y0 = cx - cw / 2, cy - ch * 0.46
    if cx < img_w * 0.35:
        x0 = 0
    elif cx > img_w * 0.65:
        x0 = img_w - cw
    if cy > img_h * 0.55:
        y0 = img_h - ch
    elif cy < img_h * 0.30:
        y0 = 0
    w, h = cw / img_w, ch / img_h
    return {"x": _clamp(x0 / img_w, 0, 1 - w), "y": _clamp(y0 / img_h, 0, 1 - h),
            "w": _clamp(w, 0.05, 1), "h": _clamp(h, 0.05, 1)}


def detect_boxes(image_path: str) -> dict:
    full = {"x": 0.0, "y": 0.0, "w": 1.0, "h": 1.0}
    try:
        import cv2  # type: ignore
    except Exception:
        return {"game": full, "cam": dict(DEFAULT_CAM), "found": False, "reason": "opencv_not_installed"}
    img = cv2.imread(image_path)
    if img is None:
        return {"game": full, "cam": dict(DEFAULT_CAM), "found": False, "reason": "bad_image"}
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=6, minSize=(int(w * 0.04), int(w * 0.04)))
    if len(faces) == 0:
        return {"game": full, "cam": dict(DEFAULT_CAM), "found": False, "reason": "no_face"}
    fx, fy, fw, fh = max(faces, key=lambda f: f[2] * f[3])
    return {"game": full, "cam": box_from_face(float(fx), float(fy), float(fw), float(fh), w, h),
            "found": True, "reason": "face"}
