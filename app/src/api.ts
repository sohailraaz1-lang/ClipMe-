import { JobState, Suggestion, ServerClip, TrendItem } from './types';
import { Box } from './layouts';

let BASE = 'http://10.0.2.2:8000';
export const setBase = (u: string) => { BASE = u.replace(/\/+$/, ''); };
export const getBase = () => BASE;
export const abs = (p?: string | null) => (!p ? undefined : p.startsWith('http') ? p : BASE + p);

async function req<T>(path: string, init?: RequestInit, timeoutMs = 30000): Promise<T> {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const r = await fetch(BASE + path, {
      ...init, signal: ctl.signal,
      headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
    });
    const text = await r.text();
    let data: any = {};
    try { data = text ? JSON.parse(text) : {}; } catch { /* non-JSON error page */ }
    if (!r.ok) throw new Error(data.detail ? String(data.detail) : `Server error (${r.status})`);
    return data as T;
  } catch (e: any) {
    if (e?.name === 'AbortError') throw new Error("The server didn't answer in time.");
    if (e?.message?.includes('Network request failed')) throw new Error("Can't reach the ClipMe server. Check the address in Settings.");
    throw e;
  } finally { clearTimeout(t); }
}
const post = <T,>(p: string, body: unknown, headers?: Record<string, string>) =>
  req<T>(p, { method: 'POST', body: JSON.stringify(body), headers });

export const health = () => req<any>('/api/health', undefined, 6000);
export const getJob = (id: string) => req<JobState>(`/api/jobs/${id}`, undefined, 10000);
export const cancelJob = (id: string) => post<any>(`/api/jobs/${id}/cancel`, {}).catch(() => null);
export const jobLog = (id: string) => req<{ log: string[] }>(`/api/jobs/${id}/log`);

/** Starts a job and polls until it finishes. Returns the job result. `handle.cancel()` aborts server-side too. */
export function runJob<T = any>(start: () => Promise<{ job_id: string }>, onUpdate: (j: JobState) => void) {
  let id: string | null = null; let cancelled = false;
  const promise = (async () => {
    const { job_id } = await start();
    id = job_id;
    let misses = 0;
    for (;;) {
      if (cancelled) throw new Error('cancelled');
      await new Promise((r) => setTimeout(r, 1000));
      let j: JobState;
      try { j = await getJob(job_id); misses = 0; } catch (e) { if (++misses > 15) throw e; continue; }
      onUpdate(j);
      if (j.status === 'done') return j.result as T;
      if (j.status === 'error') throw new Error(j.error || 'Something went wrong.');
      if (j.status === 'cancelled') throw new Error('cancelled');
    }
  })();
  return { promise, jobId: () => id, cancel: () => { cancelled = true; if (id) cancelJob(id); } };
}

export const resolve = (url: string) => post<{ job_id: string }>('/api/resolve', { url });
export const loadWindow = (url: string, start: number, end: number, is_live: boolean) =>
  post<{ job_id: string }>('/api/window', { url, start, end, is_live });
export const windowFrame = (wid: string, t: number) => req<{ frame_url: string }>(`/api/windows/${wid}/frame?t=${t.toFixed(1)}`, undefined, 60000);
export const autoBox = (wid: string, t: number) =>
  req<{ game: Box; cam: Box; found: boolean; reason: string }>(`/api/windows/${wid}/autobox?t=${t.toFixed(1)}`, undefined, 60000);
export const trending = (platform: string, game: string, lang: string) =>
  req<{ items: TrendItem[]; demo: boolean; errors: string[] }>(
    `/api/trending?platform=${platform}&game=${encodeURIComponent(game)}&lang=${encodeURIComponent(lang)}`, undefined, 20000);
export const listClips = () => req<{ clips: ServerClip[] }>('/api/clips');
export const deleteClip = (name: string) => req<any>(`/api/clips/${encodeURIComponent(name)}`, { method: 'DELETE' });
export const gemini = (windowId: string, apiKey: string, maxClips = 5) =>
  post<{ job_id: string }>('/api/gemini-auto-clip', { window_id: windowId, api_key: apiKey || undefined, max_clips: maxClips });
export const hypeScan = (url: string, start: number, end: number, viewers: number, threshold: number) =>
  post<{ job_id: string }>('/api/hype/scan', { url, start, end, viewers, threshold_pct: threshold });
export type { Suggestion };
export const render = (body: unknown) => post<{ job_id: string }>('/api/render', body);
