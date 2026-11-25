export interface StreamMeta {
  url: string; platform: 'youtube' | 'twitch'; title: string; channel: string; is_live: boolean;
  duration: number | null; live_edge: number | null; resolved_at: number; thumbnail?: string | null;
  viewers?: number | null; frame_url?: string | null;
}
export interface WindowInfo { window_id: string; start: number; end: number; duration: number; preview_url: string; strip_url: string }
export interface ClipItem { id: string; start: number; end: number; windowId: string; windowStart: number; title?: string }
export interface Suggestion { start_sec: number; end_sec: number; title: string; reason: string; viral_score: number }
export interface JobState {
  id: string; kind: string; status: 'queued' | 'running' | 'done' | 'error' | 'cancelled'; steps: string[]; step: number;
  progress: number; step_progress: number; message: string; detail: string; result: any; error: string | null; elapsed: number;
}
export interface ServerClip { name: string; url: string; thumb_url: string; size: number; duration: number; created: number }
export interface TrendItem {
  id: string; platform: 'youtube' | 'twitch'; title: string; channel: string; game: string; lang: string;
  viewers: number; url: string; thumbnail: string | null;
}
export interface Settings { serverUrl: string; geminiKey: string; hypeThreshold: number; hypeEnabled: boolean }
