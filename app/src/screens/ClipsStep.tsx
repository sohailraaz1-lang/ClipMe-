import React, { useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { Btn, Card, Label, Pill, HelpModal, H, P } from '../components/ui';
import LoadCard from '../components/LoadCard';
import ClipEditor from '../components/ClipEditor';
import * as api from '../api';
import { useApp } from '../store';
import { ClipItem, JobState, StreamMeta, Suggestion, WindowInfo } from '../types';
import { computeWindow, fmtHMS, fmtShort, maskDigits, digitsToSeconds, MAX_CLIPS, overlaps } from '../time';

interface Props {
  meta: StreamMeta; clips: ClipItem[]; setClips: (c: ClipItem[]) => void;
  win: WindowInfo | null; setWin: (w: WindowInfo | null) => void; onNext: () => void;
}
const uid = () => Math.random().toString(36).slice(2, 9);

export default function ClipsStep({ meta, clips, setClips, win, setWin, onNext }: Props) {
  const { settings } = useApp();
  const live = meta.is_live;
  const [digits, setDigits] = useState('');
  const [windowMin, setWindowMin] = useState<5 | 10>(5);
  const [job, setJob] = useState<JobState | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');
  const [help, setHelp] = useState(false);
  const [sel, setSel] = useState<{ start: number; end: number } | null>(null);
  const [added, setAdded] = useState<string>('');
  const [sugg, setSugg] = useState<Suggestion[]>([]);
  const [smartBusy, setSmartBusy] = useState('');
  const handle = useRef<ReturnType<typeof api.runJob> | null>(null);

  const edgeNow = () => (live && meta.live_edge != null ? meta.live_edge + (Date.now() / 1000 - meta.resolved_at) : meta.duration ?? 0);
  const upper = live ? edgeNow() : meta.duration;
  const secs = digitsToSeconds(digits);
  const center = live ? Math.max(0, edgeNow() - secs) : Math.min(secs, meta.duration ?? secs);

  const load = async (c: number) => {
    const [s, e] = computeWindow(c, windowMin, upper ?? null);
    setErr(''); setLoading(true); setJob(null); setAdded('');
    const h = api.runJob<WindowInfo>(() => api.loadWindow(meta.url, s, e, live), setJob);
    handle.current = h;
    try {
      const w = await h.promise; setLoading(false); setWin(w);
      const L = w.duration;
      const end = live || c + 15 > w.start + L ? w.start + L : c + 15;
      setSel({ start: Math.max(w.start, end - 30), end });
    } catch (x: any) { setLoading(false); if (x.message !== 'cancelled') setErr(x.message); }
  };

  const shift = (d: number) => { if (win) { setWin(null); load((win.start + win.end) / 2 + d); } };
  const cur = sel && win ? sel : null;

  const addClip = (s: number, e: number, title?: string) => {
    if (!win) return;
    if (clips.length >= MAX_CLIPS) { setAdded(`Up to ${MAX_CLIPS} clips at a time.`); return; }
    const hit = clips.findIndex((c) => overlaps(c, { start: s, end: e }));
    const item: ClipItem = { id: uid(), start: s, end: e, windowId: win.window_id, windowStart: win.start, title };
    setClips([...clips, item]);
    setAdded(`✓ Clip ${clips.length + 1} added` + (hit >= 0 ? `|Added — overlaps clip ${hit + 1}` : ''));
  };

  const runSmart = async (kind: 'gemini' | 'hype') => {
    if (!win) return;
    setSmartBusy(kind === 'gemini' ? 'Asking Gemini for the best moments…' : 'Scanning chat for spikes…'); setErr('');
    try {
      const h = api.runJob<{ clips: Suggestion[] }>(
        () => kind === 'gemini' ? api.gemini(win.window_id, settings.geminiKey) : api.hypeScan(meta.url, win.start, win.start + win.duration, meta.viewers ?? 0, settings.hypeThreshold),
        () => {});
      const r = await h.promise;
      setSugg(r.clips);
      if (!r.clips.length) setErr(kind === 'hype' ? 'No chat spikes in this window.' : 'No strong moments found.');
    } catch (x: any) { setErr(x.message); }
    setSmartBusy('');
  };

  useEffect(() => {   // coming back from the Layout step: re-open the player on the loaded window
    if (win && !sel) { const e = win.start + win.duration; setSel({ start: Math.max(win.start, e - 30), end: e }); }
  }, [win]);

  const stage = loading ? 'loading' : win && cur ? 'player' : 'time';

  return (
    <View>
      {clips.length > 0 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: 10 }}>
          {clips.map((c, i) => (
            <View key={c.id} style={s.chip}>
              <View style={s.chipN}><Text style={{ color: C.text, fontSize: 10, fontFamily: MONO }}>{Math.round(c.end - c.start)}s</Text></View>
              <View style={{ marginHorizontal: 8 }}><Text style={{ color: C.text, fontWeight: '700' }}>Clip {i + 1}</Text><Text style={{ color: C.muted, fontSize: 11, fontFamily: MONO }}>{fmtHMS(c.start)}</Text></View>
              <TouchableOpacity hitSlop={10} onPress={() => setClips(clips.filter((x) => x.id !== c.id))}><Feather name="x" size={16} color={C.muted} /></TouchableOpacity>
            </View>
          ))}
        </ScrollView>
      )}

      <Card>
        <Label help={() => setHelp(true)}>02 · PICK YOUR CLIPS</Label>

        {stage === 'time' && <>
          <Text style={s.q}>{live ? 'How long ago?' : 'Jump to which time?'}</Text>
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
            <View style={{ flex: 1 }}>
              <TextInput value={digits ? maskDigits(digits, live) : ''} placeholder={live ? '-0:00' : '0:00'} placeholderTextColor={C.dim}
                keyboardType="number-pad" onChangeText={(t) => setDigits(t.replace(/\D/g, '').replace(/^0+/, '').slice(0, 6))} style={s.time} />
            </View>
            <TouchableOpacity style={s.now} onPress={() => setDigits(live ? '' : '')}><Text style={{ color: C.text, fontWeight: '700' }}>{live ? 'Just now' : 'Start'}</Text></TouchableOpacity>
          </View>
          <Text style={s.hint}>
            {digits ? `≈ ${fmtHMS(center)} into the stream` + (live ? ` · stream is at ${fmtHMS(edgeNow())}` : ` · video is ${fmtHMS(meta.duration ?? 0)}`) :
              live ? `Stream is at ${fmtHMS(edgeNow())} · type how long ago, e.g. 3000 = 30 min` : `Video is ${fmtHMS(meta.duration ?? 0)} · type a time, e.g. 12740 = 1:27:40`}
          </Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 14 }}>
            <Text style={s.lab}>WINDOW</Text>
            <Pill title="5 min" active={windowMin === 5} onPress={() => setWindowMin(5)} /><Pill title="10 min" active={windowMin === 10} onPress={() => setWindowMin(10)} />
          </View>
          {!!err && <Text style={s.err}>{err}</Text>}
          <Btn title="Load this part" onPress={() => load(center)} style={{ marginTop: 16 }} />
          <Btn title="Add a clip to continue" disabled style={{ marginTop: 10 }} />
        </>}

        {stage === 'loading' && (
          <LoadCard title="LOADING THIS PART" job={job} steps={['Downloading this part…', 'Preparing preview…']}
            subs={[`${windowMin} minutes in low quality, so it's quick`, 'Making it smooth to scrub']}
            footer={live ? 'A moment that just happened may not be saved yet.' : undefined}
            onCancel={() => { handle.current?.cancel(); setLoading(false); }} />
        )}

        {stage === 'player' && win && cur && <>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text style={s.win}>WINDOW {fmtHMS(win.start)} – {fmtHMS(win.start + win.duration)}{live ? ' · LIVE' : ''}</Text>
            <TouchableOpacity onPress={() => { setWin(null); setSel(null); }}><Text style={{ color: C.lime, fontWeight: '800', fontSize: 12 }}>New time</Text></TouchableOpacity>
          </View>
          <Text style={[s.hint, { marginBottom: 10 }]}>{live ? `Stream is at ${fmtHMS(edgeNow())} — ` : ''}showing {Math.round(win.duration / 60)} min</Text>
          <ClipEditor key={win.window_id} win={win} start={cur.start} end={cur.end} onChange={(a, b) => { setSel({ start: a, end: b }); setAdded(''); }} />
          <Btn title="+ Add this clip" style={{ marginTop: 14 }} disabled={!!added.startsWith('✓')} onPress={() => addClip(cur.start, cur.end)} />
          {!!added && (
            <View style={{ marginTop: 8 }}>
              {added.split('|').map((l, i) => <Text key={i} style={{ color: i ? C.amber : C.lime, fontSize: 12, fontWeight: '700', textAlign: i ? 'left' : 'center' }}>{l}</Text>)}
            </View>
          )}

          <Text style={s.done}>Done with this part</Text>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <Btn title="← +2 min earlier" variant="outline" style={{ flex: 1, paddingVertical: 12 }} onPress={() => shift(-120)} />
            <Btn title="+2 min later →" variant="outline" style={{ flex: 1, paddingVertical: 12 }} onPress={() => shift(120)} />
          </View>
          {!!err && <Text style={s.err}>{err}</Text>}
        </>}
      </Card>

      {stage === 'player' && win && (
        <Card>
          <Label>SMART CLIPPING</Label>
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <Btn title="Auto-find best clips (Gemini)" variant="ghost" icon="zap" style={{ flex: 1, paddingVertical: 12 }} disabled={!!smartBusy} onPress={() => runSmart('gemini')} />
            <Btn title="Chat hype" variant="ghost" icon="message-circle" style={{ paddingVertical: 12 }} disabled={!!smartBusy} onPress={() => runSmart('hype')} />
          </View>
          {!!smartBusy && <Text style={[s.hint, { marginTop: 10 }]}>{smartBusy}</Text>}
          {sugg.map((g, i) => (
            <View key={i} style={s.sg}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: C.text, fontWeight: '800' }}>{g.title} <Text style={{ color: C.lime }}>· {g.viral_score}</Text></Text>
                <Text style={s.hint}>{fmtShort(g.start_sec)} → {fmtShort(g.end_sec)} · {g.reason}</Text>
              </View>
              <TouchableOpacity style={s.rej} onPress={() => setSugg(sugg.filter((_, j) => j !== i))}><Feather name="x" size={16} color={C.muted} /></TouchableOpacity>
              <TouchableOpacity style={s.acc} onPress={() => { addClip(g.start_sec, Math.min(g.end_sec, g.start_sec + 180), g.title); setSugg(sugg.filter((_, j) => j !== i)); }}><Feather name="check" size={16} color="#0B0C0E" /></TouchableOpacity>
            </View>
          ))}
        </Card>
      )}

      {stage === 'player' && <Btn title={clips.length ? `Next: Layout →` : 'Add a clip to continue'} disabled={!clips.length} onPress={onNext} />}

      <HelpModal visible={help} onClose={() => setHelp(false)} title="How moments work">
        <H>1. Type the time</H><P>Ended streams and videos: where the moment is, from the start (1:27:40). Live: how long ago it happened. Type the digits — 3000 means 30:00.</P>
        <H>2. Load this part</H><P>We load a 5 (or 10) minute window around that time in low quality, so it's quick. Nothing else is downloaded.</P>
        <H>3. Mark your clips</H><P>Drag the lime handles to the start and end (5 s to 3 min), play it, nudge by 1 s, then tap + Add this clip. You can mark several clips in the same window. Need more? ← +2 min earlier / +2 min later →.</P>
        <H>Live streams</H><P>A moment that just happened may not be saved yet. YouTube keeps up to ~5 days; Twitch only keeps saved broadcasts.</P>
        <H>Then</H><P>Up to 10 clips. Clip it renders them one after another in full quality. You can leave the app while it works.</P>
        <H>Limits</H><P>Only clip streams you have the right to.</P>
      </HelpModal>
    </View>
  );
}
const s = StyleSheet.create({
  chip: { flexDirection: 'row', alignItems: 'center', backgroundColor: C.card2, borderRadius: 12, padding: 8, marginRight: 8, borderWidth: 1, borderColor: C.border },
  chipN: { backgroundColor: '#23272D', borderRadius: 8, width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  q: { color: C.text, fontSize: 20, fontWeight: '800' },
  time: { backgroundColor: '#0F1114', borderRadius: 12, borderWidth: 1, borderColor: C.border, color: C.text, fontFamily: MONO, fontSize: 26, paddingHorizontal: 14, paddingVertical: 10 },
  now: { backgroundColor: C.card2, borderRadius: 12, paddingHorizontal: 14, justifyContent: 'center' },
  hint: { color: C.muted, fontSize: 12, marginTop: 8 }, lab: { color: C.dim, fontFamily: MONO, fontSize: 11 },
  err: { color: C.red, fontSize: 13, marginTop: 10, fontWeight: '600' },
  win: { color: C.muted, fontFamily: MONO, fontSize: 11 }, done: { color: C.muted, textAlign: 'center', marginVertical: 14, fontWeight: '700' },
  sg: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12, backgroundColor: '#0F1114', borderRadius: 12, padding: 10 },
  rej: { width: 34, height: 34, borderRadius: 17, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center' },
  acc: { width: 34, height: 34, borderRadius: 17, backgroundColor: C.lime, alignItems: 'center', justifyContent: 'center' },
});
