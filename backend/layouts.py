"""
ClipMe layout engine - single source of truth for every 9:16 layout.

A layout is compiled into a *plan*: a list of tiles placed on a 1080x1920 canvas.
Each tile says "crop this normalized box out of the source frame, cover-fit it into
this rectangle". The FFmpeg filtergraph is generated from the plan, and the mobile
app's live preview (app/src/layouts.ts) mirrors the same math (cross-checked by
tests/test_layouts_parity.py), so what you see in the editor is what gets rendered.

Nothing is ever stretched: every tile uses scale(force_original_aspect_ratio=increase)
+ crop, so aspect ratio is always preserved.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional

W, H = 1080, 1920
ASPECT = "9:16"

# id -> label. The first 7 match the reference app; the last 3 are extras.
PRESETS: Dict[str, str] = {
    "split": "Split",
    "circle_cam": "Circle cam",
    "game_only": "Game only",
    "whole_screen": "Whole screen",
    "big_cam": "Big cam",
    "cam_box": "Cam box",
    "cam_game": "Cam + game",
    "cam_only": "Cam only",
    "whole_cam": "Whole + cam",
    "cam_banner": "Cam banner",
}
_LABEL_TO_ID = {v.lower(): k for k, v in PRESETS.items()}

SPLIT_RATIOS = {"50/50": 0.50, "60/40": 0.60, "70/30": 0.70}
CIRCLE_SIZES = {"S": 300, "M": 400, "L": 500}
BOX_WIDTHS = {"S": 340, "M": 440, "L": 560}
CORNERS = ("tl", "tr", "bl", "br")
SIDE_MARGIN, TOP_MARGIN, BOTTOM_MARGIN = 48, 170, 400  # keeps clear of Reels/Shorts UI

BIG_CAM_H = 1100          # big cam: cam on top, game below
BANNER_H = 576            # cam banner height
CARD_W, CARD_CAM_H, CARD_GAME_H, CARD_GAP, CARD_R = 1000, 562, 750, 32, 36


def normalize_preset(value: str) -> str:
    v = (value or "").strip()
    if v in PRESETS:
        return v
    key = v.lower().replace("_", " ")
    if key in _LABEL_TO_ID:
        return _LABEL_TO_ID[key]
    raise ValueError(f"Unknown layout preset: {value!r}")


@dataclass
class Box:
    x: float = 0.0
    y: float = 0.0
    w: float = 1.0
    h: float = 1.0

    def clamped(self) -> "Box":
        w = min(max(self.w, 0.02), 1.0)
        h = min(max(self.h, 0.02), 1.0)
        x = min(max(self.x, 0.0), 1.0 - w)
        y = min(max(self.y, 0.0), 1.0 - h)
        return Box(x, y, w, h)


@dataclass
class LayoutSpec:
    preset: str = "split"
    split_ratio: str = "50/50"
    cam_corner: str = "br"
    cam_size: str = "M"
    game_box: Box = field(default_factory=Box)
    cam_box: Optional[Box] = None


@dataclass
class Tile:
    role: str            # 'game' | 'cam' | 'full'
    x: int
    y: int
    w: int
    h: int
    shape: str = "rect"  # 'rect' | 'round' | 'circle'
    radius: int = 0
    src: Box = field(default_factory=Box)


@dataclass
class Plan:
    tiles: List[Tile]
    bg: str = "black"    # 'black' | 'blur'

    def to_dict(self) -> dict:
        return asdict(self)


def _even(n: float) -> int:
    return max(2, int(round(n / 2.0)) * 2)


def _corner_xy(corner: str, w: int, h: int):
    if corner not in CORNERS:
        corner = "br"
    x = SIDE_MARGIN if corner[1] == "l" else W - SIDE_MARGIN - w
    y = TOP_MARGIN if corner[0] == "t" else H - BOTTOM_MARGIN - h
    return x, y


def needs_cam(preset: str) -> bool:
    return normalize_preset(preset) not in ("game_only", "whole_screen")


def target_aspects(spec: LayoutSpec, src_aspect: float = 16 / 9) -> Dict[str, Optional[float]]:
    """Pixel aspect (w/h) the GAME / CAM crop should have so the crop == the box you see.
    None means 'free' (the tile adapts to the box, or the box is unused)."""
    p = normalize_preset(spec.preset)
    g: Optional[float] = W / H
    c: Optional[float] = None
    if p == "split":
        gh = _even(H * SPLIT_RATIOS.get(spec.split_ratio, 0.5))
        g, c = W / gh, W / (H - gh)
    elif p == "big_cam":
        g, c = W / (H - BIG_CAM_H), W / BIG_CAM_H
    elif p == "circle_cam":
        c = 1.0
    elif p == "cam_box":
        c = None
    elif p == "cam_game":
        g, c = CARD_W / CARD_GAME_H, CARD_W / CARD_CAM_H
    elif p == "cam_only":
        g, c = None, W / H
    elif p == "cam_banner":
        g, c = W / (H - BANNER_H), W / BANNER_H
    elif p == "whole_screen":
        g = None
    elif p == "whole_cam":
        g, c = None, 720 / 540
    return {"game": g, "cam": c}


def layout_plan(spec: LayoutSpec, src_aspect: float = 16 / 9) -> Plan:
    p = normalize_preset(spec.preset)
    full = Box(0, 0, 1, 1)
    gbox = spec.game_box.clamped()
    cbox = spec.cam_box.clamped() if spec.cam_box else None

    # A layout that needs a webcam but has none degrades to the game-only crop.
    if cbox is None and needs_cam(p):
        p = "game_only"

    if p == "game_only":
        return Plan([Tile("game", 0, 0, W, H, src=gbox)])

    if p == "whole_screen":
        h = _even(W / src_aspect)
        return Plan([Tile("full", 0, (H - h) // 2, W, h, src=full)], bg="blur")

    if p == "split":
        gh = _even(H * SPLIT_RATIOS.get(spec.split_ratio, 0.5))
        return Plan([Tile("game", 0, 0, W, gh, src=gbox), Tile("cam", 0, gh, W, H - gh, src=cbox)])

    if p == "big_cam":
        return Plan([Tile("cam", 0, 0, W, BIG_CAM_H, src=cbox),
                     Tile("game", 0, BIG_CAM_H, W, H - BIG_CAM_H, src=gbox)])

    if p == "circle_cam":
        d = CIRCLE_SIZES.get(spec.cam_size, 400)
        x, y = _corner_xy(spec.cam_corner, d, d)
        return Plan([Tile("game", 0, 0, W, H, src=gbox),
                     Tile("cam", x, y, d, d, "circle", d // 2, cbox)])

    if p == "cam_box":
        w = BOX_WIDTHS.get(spec.cam_size, 440)
        cam_aspect = (cbox.w / cbox.h) * src_aspect
        h = min(_even(w / cam_aspect), 760)
        x, y = _corner_xy(spec.cam_corner, w, h)
        return Plan([Tile("game", 0, 0, W, H, src=gbox),
                     Tile("cam", x, y, w, h, "round", 44, cbox)])

    if p == "cam_game":
        total = CARD_CAM_H + CARD_GAP + CARD_GAME_H
        y0 = (H - total) // 2
        x0 = (W - CARD_W) // 2
        return Plan([Tile("cam", x0, y0, CARD_W, CARD_CAM_H, "round", CARD_R, cbox),
                     Tile("game", x0, y0 + CARD_CAM_H + CARD_GAP, CARD_W, CARD_GAME_H, "round", CARD_R, gbox)],
                    bg="blur")

    if p == "cam_only":
        return Plan([Tile("cam", 0, 0, W, H, src=cbox)])

    if p == "whole_cam":
        h = _even(W / src_aspect)
        y = 260
        return Plan([Tile("full", 0, y, W, h, src=full),
                     Tile("cam", (W - 720) // 2, y + h + 90, 720, 540, "round", 44, cbox)], bg="blur")

    if p == "cam_banner":
        return Plan([Tile("cam", 0, 0, W, BANNER_H, src=cbox),
                     Tile("game", 0, BANNER_H, W, H - BANNER_H, src=gbox)])

    raise ValueError(p)


def fit_box_aspect(box: Box, target: Optional[float], src_aspect: float = 16 / 9) -> Box:
    """Resize `box` (about its centre) so its *pixel* aspect equals `target`."""
    if not target:
        return box.clamped()
    k = src_aspect / target            # normalized h = w * k
    w = box.w
    h = w * k
    if h > 1.0:
        h, w = 1.0, 1.0 / k
    if w > 1.0:
        w, h = 1.0, k
    cx, cy = box.x + box.w / 2, box.y + box.h / 2
    return Box(cx - w / 2, cy - h / 2, w, h).clamped()
