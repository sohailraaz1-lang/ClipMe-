// Mirror of backend/layouts.py. Keep in sync - tests/test_layouts_parity.py compares both.
export const W = 1080;
export const H = 1920;

export type PresetId =
  | 'split' | 'circle_cam' | 'game_only' | 'whole_screen' | 'big_cam'
  | 'cam_box' | 'cam_game' | 'cam_only' | 'whole_cam' | 'cam_banner';
export type Corner = 'tl' | 'tr' | 'bl' | 'br';
export type SizeKey = 'S' | 'M' | 'L';
export type SplitRatio = '50/50' | '60/40' | '70/30';

export interface Box { x: number; y: number; w: number; h: number }
export interface LayoutSpec {
  preset: PresetId; splitRatio: SplitRatio; camCorner: Corner; camSize: SizeKey;
  gameBox: Box; camBox: Box | null;
}
export interface Tile {
  role: 'game' | 'cam' | 'full'; x: number; y: number; w: number; h: number;
  shape: 'rect' | 'round' | 'circle'; radius: number; src: Box;
}
export interface Plan { tiles: Tile[]; bg: 'black' | 'blur' }

export const PRESETS: { id: PresetId; label: string; extra?: boolean; blurb: string }[] = [
  { id: 'split', label: 'Split', blurb: 'Game on top, you below. Pick how big the game is.' },
  { id: 'circle_cam', label: 'Circle cam', blurb: 'Game fills the screen, you in a round bubble.' },
  { id: 'game_only', label: 'Game only', blurb: 'Game fills the screen. No facecam.' },
  { id: 'whole_screen', label: 'Whole screen', blurb: 'The whole picture in the middle, blurred fill above and below.' },
  { id: 'big_cam', label: 'Big cam', blurb: 'You big on top, the game below.' },
  { id: 'cam_box', label: 'Cam box', blurb: 'Game fills the screen, you in a rounded box.' },
  { id: 'cam_game', label: 'Cam + game', blurb: 'You and the game as two cards on a blurred background.' },
  { id: 'cam_only', label: 'Cam only', extra: true, blurb: 'Just you, full screen.' },
  { id: 'whole_cam', label: 'Whole + cam', extra: true, blurb: 'Whole picture on top, you in a card below.' },
  { id: 'cam_banner', label: 'Cam banner', extra: true, blurb: 'A cam strip across the top, game below.' },
];

const SPLIT: Record<SplitRatio, number> = { '50/50': 0.5, '60/40': 0.6, '70/30': 0.7 };
const CIRCLE: Record<SizeKey, number> = { S: 300, M: 400, L: 500 };
const BOXW: Record<SizeKey, number> = { S: 340, M: 440, L: 560 };
const SIDE = 48, TOP = 170, BOTTOM = 400;
const BIG_CAM_H = 1100, BANNER_H = 576;
const CARD_W = 1000, CARD_CAM_H = 562, CARD_GAME_H = 750, CARD_GAP = 32, CARD_R = 36;

const FULL: Box = { x: 0, y: 0, w: 1, h: 1 };
// Python's round() is banker's rounding; JS Math.round is half-up. Match Python.
const pyRound = (n: number) => {
  const f = Math.floor(n), d = n - f;
  if (d < 0.5) return f;
  if (d > 0.5) return f + 1;
  return f % 2 === 0 ? f : f + 1;
};
const even = (n: number) => Math.max(2, pyRound(n / 2) * 2);

export const clampBox = (b: Box): Box => {
  const w = Math.min(Math.max(b.w, 0.02), 1);
  const h = Math.min(Math.max(b.h, 0.02), 1);
  return { x: Math.min(Math.max(b.x, 0), 1 - w), y: Math.min(Math.max(b.y, 0), 1 - h), w, h };
};

const cornerXY = (c: Corner, w: number, h: number) => ({
  x: c[1] === 'l' ? SIDE : W - SIDE - w,
  y: c[0] === 't' ? TOP : H - BOTTOM - h,
});

export const needsCam = (p: PresetId) => p !== 'game_only' && p !== 'whole_screen';
export const needsGame = (p: PresetId) => p !== 'whole_screen' && p !== 'whole_cam' && p !== 'cam_only';

export function targetAspects(s: Pick<LayoutSpec, 'preset' | 'splitRatio'>): { game: number | null; cam: number | null } {
  let g: number | null = W / H, c: number | null = null;
  switch (s.preset) {
    case 'split': { const gh = even(H * (SPLIT[s.splitRatio] ?? 0.5)); g = W / gh; c = W / (H - gh); break; }
    case 'big_cam': g = W / (H - BIG_CAM_H); c = W / BIG_CAM_H; break;
    case 'circle_cam': c = 1; break;
    case 'cam_game': g = CARD_W / CARD_GAME_H; c = CARD_W / CARD_CAM_H; break;
    case 'cam_only': g = null; c = W / H; break;
    case 'cam_banner': g = W / (H - BANNER_H); c = W / BANNER_H; break;
    case 'whole_screen': g = null; break;
    case 'whole_cam': g = null; c = 720 / 540; break;
    default: break;
  }
  return { game: g, cam: c };
}

export function layoutPlan(s: LayoutSpec, srcAspect = 16 / 9): Plan {
  let p = s.preset;
  const g = clampBox(s.gameBox);
  const c = s.camBox ? clampBox(s.camBox) : null;
  if (!c && needsCam(p)) p = 'game_only';
  const T = (role: Tile['role'], x: number, y: number, w: number, h: number, src: Box,
            shape: Tile['shape'] = 'rect', radius = 0): Tile => ({ role, x, y, w, h, shape, radius, src });
  switch (p) {
    case 'game_only': return { tiles: [T('game', 0, 0, W, H, g)], bg: 'black' };
    case 'whole_screen': { const h = even(W / srcAspect); return { tiles: [T('full', 0, Math.floor((H - h) / 2), W, h, FULL)], bg: 'blur' }; }
    case 'split': { const gh = even(H * (SPLIT[s.splitRatio] ?? 0.5));
      return { tiles: [T('game', 0, 0, W, gh, g), T('cam', 0, gh, W, H - gh, c!)], bg: 'black' }; }
    case 'big_cam': return { tiles: [T('cam', 0, 0, W, BIG_CAM_H, c!), T('game', 0, BIG_CAM_H, W, H - BIG_CAM_H, g)], bg: 'black' };
    case 'circle_cam': { const d = CIRCLE[s.camSize] ?? 400; const { x, y } = cornerXY(s.camCorner, d, d);
      return { tiles: [T('game', 0, 0, W, H, g), T('cam', x, y, d, d, c!, 'circle', Math.floor(d / 2))], bg: 'black' }; }
    case 'cam_box': { const w = BOXW[s.camSize] ?? 440; const h = Math.min(even(w / ((c!.w / c!.h) * srcAspect)), 760);
      const { x, y } = cornerXY(s.camCorner, w, h);
      return { tiles: [T('game', 0, 0, W, H, g), T('cam', x, y, w, h, c!, 'round', 44)], bg: 'black' }; }
    case 'cam_game': { const total = CARD_CAM_H + CARD_GAP + CARD_GAME_H; const y0 = Math.floor((H - total) / 2); const x0 = Math.floor((W - CARD_W) / 2);
      return { tiles: [T('cam', x0, y0, CARD_W, CARD_CAM_H, c!, 'round', CARD_R),
                       T('game', x0, y0 + CARD_CAM_H + CARD_GAP, CARD_W, CARD_GAME_H, g, 'round', CARD_R)], bg: 'blur' }; }
    case 'cam_only': return { tiles: [T('cam', 0, 0, W, H, c!)], bg: 'black' };
    case 'whole_cam': { const h = even(W / srcAspect); const y = 260;
      return { tiles: [T('full', 0, y, W, h, FULL), T('cam', Math.floor((W - 720) / 2), y + h + 90, 720, 540, c!, 'round', 44)], bg: 'blur' }; }
    case 'cam_banner': return { tiles: [T('cam', 0, 0, W, BANNER_H, c!), T('game', 0, BANNER_H, W, H - BANNER_H, g)], bg: 'black' };
  }
}

/** Resize `box` about its centre so its pixel aspect equals `target` (no-op if target is null). */
export function fitBoxAspect(b: Box, target: number | null, srcAspect = 16 / 9): Box {
  if (!target) return clampBox(b);
  const k = srcAspect / target;
  let w = b.w, h = w * k;
  if (h > 1) { h = 1; w = 1 / k; }
  if (w > 1) { w = 1; h = k; }
  const cx = b.x + b.w / 2, cy = b.y + b.h / 2;
  return clampBox({ x: cx - w / 2, y: cy - h / 2, w, h });
}

export const DEFAULT_CAM: Box = { x: 0, y: 0.62, w: 0.24, h: 0.38 };
export const defaultLayout = (): LayoutSpec => ({
  preset: 'split', splitRatio: '50/50', camCorner: 'br', camSize: 'M',
  gameBox: { ...FULL }, camBox: { ...DEFAULT_CAM },
});
