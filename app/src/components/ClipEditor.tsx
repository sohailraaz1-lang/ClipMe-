import React, { useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, Image, TouchableOpacity, PanResponder, StyleSheet, LayoutChangeEvent } from 'react-native';
import { useVideoPlayer, VideoView } from 'expo-video';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { fmtHMS, fmtShort, MIN_CLIP, MAX_CLIP } from '../time';
import { WindowInfo } from '../types';
import { abs } from '../api';

interface Props {
  win: WindowInfo; start: number; end: number;           // clip, absolute stream seconds
  onChange: (start: number, end: number) => void;
  onFrame?: (t: number) => void;
}
const HW = 22; // handle width

export default function ClipEditor({ win, start, end, onChange }: Props) {
  const L = Math.max(1, win.duration);
  const rel = (t: number) => t - win.start;
  const player = useVideoPlayer(abs(win.preview_url)!, (p) => { p.loop = false; p.timeUpdateEventInterval = 0.1; });
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  const [now, setNow] = useState(rel(start));
  const [ready, setReady] = useState(false);
  const clipRef = useRef({ start, end }); clipRef.current = { start, end };

  useEffect(() => {
    const a = player.addListener('timeUpdate', (e: any) => {
      const t = e.currentTime as number; setNow(t);
      const c = clipRef.current;
      if (t >= rel(c.end) - 0.05 || t < rel(c.start) - 1.5) player.currentTime = rel(c.start);   // loop the clip
    });
    const b = player.addListener('playingChange', (e: any) => setPlaying(!!e.isPlaying));
    const c = player.addListener('statusChange', (e: any) => { if (e.status === 'readyToPlay') setReady(true); });
    return () => { a.remove(); b.remove(); c.remove(); };
  }, [player, win.window_id]);
  useEffect(() => { player.muted = muted; }, [muted, player]);
  useEffect(() => { try { player.currentTime = rel(start); } catch { /* not ready */ } player.play(); }, [ready]);

  // ---- trimmer ---------------------------------------------------------------------------
  const [tw, setTw] = useState(0);
  const [drag, setDrag] = useState<null | 'l' | 'r' | 'm'>(null);
  const live = useRef({ tw, L, start, end, onChange }); live.current = { tw, L, start, end, onChange };
  const begin = useRef({ start, end });
  const seek = (t: number) => { try { player.currentTime = Math.max(0, Math.min(L, rel(t))); } catch { /* ignore */ } };

  const mk = (kind: 'l' | 'r' | 'm') => useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onPanResponderGrant: () => { begin.current = { start: live.current.start, end: live.current.end }; setDrag(kind); },
    onPanResponderMove: (_e, g) => {
      const { tw: w, L: len, onChange: oc } = live.current; if (!w) return;
      const dt = (g.dx / w) * len; const b = begin.current;
      const lo = win.start, hi = win.start + len;
      if (kind === 'l') {
        const s = Math.min(Math.max(b.start + dt, lo, b.end - MAX_CLIP), b.end - MIN_CLIP);
        oc(round1(s), b.end); seek(s);
      } else if (kind === 'r') {
        const e = Math.max(Math.min(b.end + dt, hi, b.start + MAX_CLIP), b.start + MIN_CLIP);
        oc(b.start, round1(e)); seek(e - 0.5);
      } else {
        const d = b.end - b.start; const s = Math.min(Math.max(b.start + dt, lo), hi - d);
        oc(round1(s), round1(s + d)); seek(s);
      }
    },
    onPanResponderRelease: () => setDrag(null), onPanResponderTerminate: () => setDrag(null),
  }), []);
  const pl = mk('l'), pr = mk('r'), pm = mk('m');

  const x = (t: number) => ((t - win.start) / L) * tw;
  const set = (s: number, e: number) => {
    const lo = win.start, hi = win.start + L;
    s = Math.max(lo, s); e = Math.min(hi, e);
    if (e - s < MIN_CLIP || e - s > MAX_CLIP) return;
    onChange(round1(s), round1(e)); seek(s);
  };
  const dur = end - start;

  return (
    <View>
      <View style={st.vid}>
        <VideoView player={player} style={StyleSheet.absoluteFill} contentFit="contain" nativeControls={false} />
        {!ready && <View style={st.loading}><Text style={{ color: C.muted, fontFamily: MONO, fontSize: 12 }}>Loading preview…</Text></View>}
        <TouchableOpacity style={st.mute} onPress={() => setMuted(!muted)}><Feather name={muted ? 'volume-x' : 'volume-2'} size={16} color="#0B0C0E" /></TouchableOpacity>
      </View>

      <View style={{ marginTop: 12 }} onLayout={(e: LayoutChangeEvent) => setTw(e.nativeEvent.layout.width)}>
        <View style={{ height: 56, borderRadius: 8, overflow: 'hidden', backgroundColor: '#0A0B0D' }}>
          <Image source={{ uri: abs(win.strip_url) }} style={{ width: '100%', height: 56, opacity: 0.55 }} resizeMode="stretch" />
        </View>
        {tw > 0 && <>
          <View style={[st.sel, { left: x(start), width: Math.max(HW * 2, x(end) - x(start)) }]} {...pm.panHandlers} />
          <View style={[st.handle, { left: x(start) - 2 }]} {...pl.panHandlers} hitSlop={{ top: 10, bottom: 10, left: 14, right: 8 }}><View style={st.grip} /></View>
          <View style={[st.handle, { left: x(end) - HW + 2 }]} {...pr.panHandlers} hitSlop={{ top: 10, bottom: 10, left: 8, right: 14 }}><View style={st.grip} /></View>
          <View pointerEvents="none" style={[st.head, { left: Math.max(0, Math.min(tw, x(win.start + now))) }]} />
          {drag && <View style={[st.tip, { left: Math.max(0, Math.min(tw - 70, (drag === 'r' ? x(end) : x(start)) - 30)) }]}><Text style={st.tipT}>{fmtHMS(drag === 'r' ? end : start, false)}</Text></View>}
        </>}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
          <Text style={st.axis}>{fmtShort(win.start)}</Text><Text style={st.axis}>{fmtShort(win.start + L)}</Text>
        </View>
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 10 }}>
        <TouchableOpacity style={st.play} onPress={() => (playing ? player.pause() : player.play())}>
          <Feather name={playing ? 'pause' : 'play'} size={20} color="#0B0C0E" />
        </TouchableOpacity>
        <Text style={st.time}>{fmtHMS(win.start + now, true)}</Text>
        <View style={{ flex: 1 }} />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}><Feather name="repeat" size={11} color={C.muted} /><Text style={st.loops}>LOOPS</Text></View>
      </View>
      <Text style={st.info}>Clip {fmtHMS(start, true)} → {fmtHMS(end, true)} · {dur.toFixed(1)} s</Text>

      {/* precision controls */}
      <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
        <Nudge label="Start" onMinus={() => set(start - 1, end)} onPlus={() => set(start + 1, end)} />
        <Nudge label="End" onMinus={() => set(start, end - 1)} onPlus={() => set(start, end + 1)} />
      </View>
      <View style={{ flexDirection: 'row', gap: 8, marginTop: 8 }}>
        {[15, 30, 60, 180].map((d) => (
          <TouchableOpacity key={d} style={[st.preset, Math.abs(dur - d) < 0.5 && { backgroundColor: C.lime }]}
            onPress={() => { const e = Math.min(win.start + L, start + d); const s = Math.max(win.start, e - d); set(s, e); }}>
            <Text style={[st.presetT, Math.abs(dur - d) < 0.5 && { color: '#0B0C0E' }]}>{d}s</Text>
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}
const round1 = (n: number) => Math.round(n * 10) / 10;

function Nudge({ label, onMinus, onPlus }: { label: string; onMinus: () => void; onPlus: () => void }) {
  return (
    <View style={st.nudge}>
      <TouchableOpacity onPress={onMinus} style={st.nb}><Text style={st.nbT}>−1s</Text></TouchableOpacity>
      <Text style={st.nl}>{label}</Text>
      <TouchableOpacity onPress={onPlus} style={st.nb}><Text style={st.nbT}>+1s</Text></TouchableOpacity>
    </View>
  );
}

const st = StyleSheet.create({
  vid: { height: 200, backgroundColor: '#000', borderRadius: 12, overflow: 'hidden', borderWidth: 1, borderColor: C.border },
  loading: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center' },
  mute: { position: 'absolute', right: 8, bottom: 8, width: 30, height: 30, borderRadius: 15, backgroundColor: C.lime, alignItems: 'center', justifyContent: 'center' },
  sel: { position: 'absolute', top: 0, height: 56, borderTopWidth: 2, borderBottomWidth: 2, borderColor: C.lime, backgroundColor: C.lime + '22' },
  handle: { position: 'absolute', top: -2, width: HW, height: 60, borderRadius: 6, backgroundColor: C.lime, alignItems: 'center', justifyContent: 'center' },
  grip: { width: 3, height: 22, borderRadius: 2, backgroundColor: '#0B0C0E' },
  head: { position: 'absolute', top: -4, width: 2, height: 64, backgroundColor: '#fff' },
  tip: { position: 'absolute', top: -30, backgroundColor: C.lime, borderRadius: 6, paddingHorizontal: 7, paddingVertical: 3 },
  tipT: { color: '#0B0C0E', fontWeight: '800', fontSize: 11 },
  axis: { color: C.muted, fontFamily: MONO, fontSize: 10 },
  play: { width: 42, height: 42, borderRadius: 21, backgroundColor: C.lime, alignItems: 'center', justifyContent: 'center' },
  time: { color: C.text, fontFamily: MONO, fontSize: 18, fontWeight: '700', marginLeft: 12 },
  loops: { color: C.muted, fontFamily: MONO, fontSize: 10 },
  info: { color: C.muted, fontFamily: MONO, fontSize: 12, marginTop: 8 },
  nudge: { flex: 1, flexDirection: 'row', alignItems: 'center', backgroundColor: C.card2, borderRadius: 12, padding: 4 },
  nb: { paddingVertical: 8, paddingHorizontal: 10, backgroundColor: '#23272D', borderRadius: 9 }, nbT: { color: C.text, fontWeight: '700', fontSize: 12 },
  nl: { flex: 1, textAlign: 'center', color: C.muted, fontSize: 12, fontWeight: '700' },
  preset: { flex: 1, alignItems: 'center', backgroundColor: C.card2, borderRadius: 10, paddingVertical: 9 }, presetT: { color: C.text, fontWeight: '800', fontSize: 13 },
});
