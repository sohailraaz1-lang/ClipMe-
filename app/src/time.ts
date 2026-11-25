// Time helpers shared by the UI. Mirrors backend/clip_engine.py (digits_to_seconds, compute_window, fmt_hms).
export const MIN_CLIP = 5;
export const MAX_CLIP = 180;
export const MAX_CLIPS = 10;

export function fmtHMS(sec: number, tenths = false): string {
  const t = Math.max(0, sec);
  const s = Math.floor(t);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const base = `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`;
  return tenths ? `${base}.${Math.floor((t - s) * 10)}` : base;
}
export const fmtShort = (sec: number) => {
  const s = Math.max(0, Math.round(sec));
  return s >= 3600 ? fmtHMS(s) : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** Keypad entry: last 2 digits = seconds, previous 2 = minutes, rest = hours. "3000" -> 30:00. */
export function digitsToSeconds(digits: string): number {
  const d = digits.replace(/\D/g, '').slice(-6).padStart(6, '0');
  return Number(d.slice(0, 2)) * 3600 + Number(d.slice(2, 4)) * 60 + Number(d.slice(4));
}
export function maskDigits(digits: string, negative: boolean): string {
  const s = digitsToSeconds(digits);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const body = h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
  return (negative ? '-' : '') + body;
}
export function computeWindow(center: number, windowMin: number, upper: number | null): [number, number] {
  const span = windowMin * 60;
  let start = Math.max(0, center - span / 2);
  let end = start + span;
  if (upper !== null && end > upper) { end = upper; start = Math.max(0, end - span); }
  return [start, end];
}
export const overlaps = (a: { start: number; end: number }, b: { start: number; end: number }) =>
  a.start < b.end && b.start < a.end;
