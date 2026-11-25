import React, { useEffect, useMemo, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, Image, StyleSheet } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { Btn, Card, Label, Seg, Toggle, HelpModal, H, P } from '../components/ui';
import LayoutThumb from '../components/LayoutThumb';
import FrameBoxes from '../components/FrameBoxes';
import ResultPreview from '../components/ResultPreview';
import * as api from '../api';
import { ClipItem } from '../types';
import { Box, Corner, LayoutSpec, PRESETS, SizeKey, SplitRatio, fitBoxAspect, needsCam, needsGame, targetAspects } from '../layouts';
import { fmtHMS } from '../time';

export interface RenderCfg {
  spec: LayoutSpec; captions: boolean; captionStyle: 'bold' | 'clean' | 'box'; font: 'bold' | 'narrow' | 'grotesk' | 'mono';
  hook: string; hookPos: 'top' | 'middle' | 'bottom'; quality: 'fast' | 'balanced' | 'high'; fps: 30 | 60;
}
const ARROWS: Record<Corner, any> = { tl: 'arrow-up-left', tr: 'arrow-up-right', bl: 'arrow-down-left', br: 'arrow-down-right' };

export default function LayoutStep({ cfg, setCfg, clips, onRender, busy }: {
  cfg: RenderCfg; setCfg: React.Dispatch<React.SetStateAction<RenderCfg>>; clips: ClipItem[]; onRender: () => void; busy: boolean;
}) {
  const { spec } = cfg;
  const first = clips[0];
  const [frame, setFrame] = useState<string | undefined>();
  const [aspect, setAspect] = useState(16 / 9);
  const [auto, setAuto] = useState<{ found: boolean; cam: Box; game: Box } | null>(null);
  const [more, setMore] = useState(false);
  const [help, setHelp] = useState(false);
  const [frameErr, setFrameErr] = useState('');
  const upd = (p: Partial<RenderCfg>) => setCfg((c) => ({ ...c, ...p }));
  const updSpec = (p: Partial<LayoutSpec>) => setCfg((c) => ({ ...c, spec: { ...c.spec, ...p } }));
  const total = clips.reduce((a, c) => a + (c.end - c.start), 0);
  const t = first ? Math.max(0, first.start - first.windowStart) : 0;

  // grab one frame from the first clip + auto-detect the facecam
  useEffect(() => {
    if (!first) return;
    let live = true;
    (async () => {
      try {
        const ab = await api.autoBox(first.windowId, t);
        const f = await api.windowFrame(first.windowId, t);
        if (!live) return;
        const uri = api.abs(f.frame_url)!;
        Image.getSize(uri, (w, h) => live && setAspect(w / h));
        setFrame(uri); setAuto(ab);
        setCfg((c) => { const T = targetAspects(c.spec); return { ...c, spec: { ...c.spec, camBox: T.cam ? fitBoxAspect(ab.cam, T.cam, 16 / 9) : ab.cam } }; });
      } catch (e: any) { if (live) setFrameErr(e.message); }
    })();
    return () => { live = false; };
  }, [first?.windowId]);

  // keep each box's pixel aspect exactly equal to what the layout needs (no stretching, ever)
  const targets = useMemo(() => targetAspects(spec), [spec.preset, spec.splitRatio]);
  useEffect(() => {
    const g = targets.game ? fitBoxAspect(spec.gameBox, targets.game, aspect) : spec.gameBox;
    const c = spec.camBox && targets.cam ? fitBoxAspect(spec.camBox, targets.cam, aspect) : spec.camBox;
    if (!sameBox(g, spec.gameBox) || (c && spec.camBox && !sameBox(c, spec.camBox))) updSpec({ gameBox: g, camBox: c });
  }, [spec.preset, spec.splitRatio, aspect]);

  const resetAuto = () => auto && updSpec({
    camBox: targets.cam ? fitBoxAspect(auto.cam, targets.cam, aspect) : auto.cam,
    gameBox: targets.game ? fitBoxAspect(auto.game, targets.game, aspect) : auto.game,
  });
  const blurb = PRESETS.find((p) => p.id === spec.preset)!.blurb;
  const wholeBanner = spec.preset === 'whole_screen' || spec.preset === 'whole_cam';

  return (
    <View>
      <Card>
        <Label help={() => setHelp(true)}>03 · LAYOUT & CAPTIONS</Label>
        <View style={s.cap}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={s.capT}>{cfg.captions ? 'On · English' : 'Off'}</Text><Toggle value={cfg.captions} onChange={(v) => upd({ captions: v })} />
          </View>
          <Text style={s.hint}>Word by word, burned in. Off (default) = the fastest export — add captions in CapCut. On needs the speech model on the server.</Text>
        </View>

        <Text style={s.pick}>Pick a style, then place the boxes</Text>
        <View style={s.grid}>
          {PRESETS.map((p) => (
            <TouchableOpacity key={p.id} onPress={() => updSpec({ preset: p.id })} style={[s.cell, spec.preset === p.id && s.cellA]}>
              <LayoutThumb id={p.id} active={spec.preset === p.id} />
              <Text style={[s.cellT, spec.preset === p.id && { color: C.lime }]} numberOfLines={1}>{p.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={{ marginTop: 14 }}>
          <FrameBoxes uri={frame} aspect={aspect} game={spec.gameBox} cam={spec.camBox}
            showGame={needsGame(spec.preset)} showCam={needsCam(spec.preset)} targets={targets}
            onChange={(r, b) => r === 'game' ? updSpec({ gameBox: b }) : updSpec({ camBox: b })} />
          {wholeBanner && <View style={s.banner}><Text style={s.bannerT}>WHOLE FRAME IS USED</Text></View>}
          {!frame && !frameErr && <Text style={s.hint}>Getting a frame…</Text>}
          {!!frameErr && <Text style={[s.hint, { color: C.red }]}>{frameErr}</Text>}
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={[s.hint, { flex: 1 }]}>Frame from the stream at {fmtHMS(first?.start ?? 0)} — boxes apply to every moment. Drag a box to move it, a corner to resize.</Text>
            <TouchableOpacity onPress={resetAuto}><Text style={{ color: C.lime, fontWeight: '800', fontSize: 12 }}>Reset to auto</Text></TouchableOpacity>
          </View>
        </View>

        <View style={{ flexDirection: 'row', marginTop: 14, gap: 14 }}>
          <ResultPreview uri={frame} aspect={aspect} width={112} spec={cfg.spec} hook={more ? cfg.hook : ''} />
          <View style={{ flex: 1 }}>
            <Text style={{ color: C.text, fontWeight: '700', marginBottom: 10 }}>{blurb}</Text>
            {spec.preset === 'split' && <><Text style={s.lab}>GAME SIZE</Text>
              <Seg<SplitRatio> options={['50/50', '60/40', '70/30']} value={spec.splitRatio} onChange={(v) => updSpec({ splitRatio: v })} /></>}
            {(spec.preset === 'circle_cam' || spec.preset === 'cam_box') && <>
              <Text style={s.lab}>CAM CORNER</Text>
              <View style={{ flexDirection: 'row', gap: 6 }}>
                {(['tl', 'tr', 'bl', 'br'] as Corner[]).map((c) => (
                  <TouchableOpacity key={c} onPress={() => updSpec({ camCorner: c })} style={[s.corner, spec.camCorner === c && { backgroundColor: C.lime }]}>
                    <Feather name={ARROWS[c]} size={16} color={spec.camCorner === c ? '#0B0C0E' : C.text} /></TouchableOpacity>))}
              </View>
              <Text style={[s.lab, { marginTop: 10 }]}>CAM SIZE</Text>
              <Seg<SizeKey> options={['S', 'M', 'L']} value={spec.camSize} onChange={(v) => updSpec({ camSize: v })} /></>}
          </View>
        </View>
        <Text style={[s.hint, { color: auto?.found ? C.lime : C.muted }]}>
          {needsCam(spec.preset) ? (auto?.found ? '✓ Facecam found and boxed for you. Not right? Drag the pink CAM box.' : 'Couldn’t auto-find a facecam — drag the pink CAM box onto it.') : ' '}
        </Text>

        <TouchableOpacity onPress={() => setMore(!more)} style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 8 }}>
          <Text style={s.lab}>MORE OPTIONS</Text><Feather name={more ? 'x' : 'plus'} size={14} color={C.muted} />
        </TouchableOpacity>
        {more && (
          <View style={s.moreBox}>
            <Text style={s.lab}>CAPTIONS & HOOK</Text>
            <Text style={s.lab}>CAPTION STYLE</Text>
            <Seg options={['bold', 'clean', 'box'] as const} value={cfg.captionStyle} onChange={(v) => upd({ captionStyle: v })} labels={{ bold: 'Bold', clean: 'Clean', box: 'Box' }} />
            <Text style={[s.lab, { marginTop: 12 }]}>FONT</Text>
            <Seg options={['bold', 'narrow', 'grotesk', 'mono'] as const} value={cfg.font} onChange={(v) => upd({ font: v })} labels={{ bold: 'Bold', narrow: 'Narrow', grotesk: 'Grotesk', mono: 'Mono' }} />
            <Text style={[s.lab, { marginTop: 12 }]}>HOOK TEXT</Text>
            <TextInput value={cfg.hook} onChangeText={(v) => upd({ hook: v.slice(0, 60) })} placeholder="Optional, e.g. Wait for it…" placeholderTextColor={C.dim} style={s.input} />
            <Text style={s.hint}>Shown for the first 3 seconds.</Text>
            <Text style={[s.lab, { marginTop: 12 }]}>HOOK POSITION</Text>
            <Seg options={['top', 'middle', 'bottom'] as const} value={cfg.hookPos} onChange={(v) => upd({ hookPos: v })} labels={{ top: 'Top', middle: 'Middle', bottom: 'Bottom' }} />
            <Text style={[s.lab, { marginTop: 12 }]}>QUALITY</Text>
            <Seg options={['fast', 'balanced', 'high'] as const} value={cfg.quality} onChange={(v) => upd({ quality: v })} labels={{ fast: 'Fast', balanced: 'Balanced', high: 'High' }} />
            <Text style={[s.lab, { marginTop: 12 }]}>FRAME RATE</Text>
            <Seg options={['30', '60'] as const} value={String(cfg.fps) as '30' | '60'} onChange={(v) => upd({ fps: Number(v) as 30 | 60 })} labels={{ '30': '30 fps', '60': '60 fps' }} />
          </View>
        )}
      </Card>
      <Btn title={busy ? 'Working…' : `Clip it — ${clips.length} clip${clips.length === 1 ? '' : 's'}`} disabled={busy || !clips.length} onPress={onRender} />
      <Text style={[s.hint, { textAlign: 'center' }]}>{clips.length} clip{clips.length === 1 ? '' : 's'} · {Math.round(total)} s total · 1080×1920 (9:16)</Text>

      <HelpModal visible={help} onClose={() => setHelp(false)} title="Layouts & boxes">
        <P>Pick a style, then place the boxes once — they apply to every clip.</P>
        <H>GAME box (lime)</H><P>What counts as gameplay. It keeps the exact shape the layout needs, so nothing is stretched.</P>
        <H>CAM box (pink)</H><P>Your facecam. We try to find it automatically; drag it if it's off. “Reset to auto” goes back.</P>
        <H>Reels / Shorts safe zones</H><P>Bubbles and boxes sit clear of the buttons and captions the apps draw at the bottom and top.</P>
      </HelpModal>
    </View>
  );
}
const sameBox = (a: Box, b: Box) => Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.w - b.w) + Math.abs(a.h - b.h) < 1e-4;

const s = StyleSheet.create({
  cap: { backgroundColor: '#0F1114', borderRadius: 14, borderWidth: 1, borderColor: C.border, padding: 14 },
  capT: { color: C.text, fontSize: 17, fontWeight: '800' }, hint: { color: C.muted, fontSize: 12, marginTop: 8 },
  pick: { color: C.text, fontWeight: '800', fontSize: 16, marginTop: 18, marginBottom: 12 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  cell: { width: '23.2%', alignItems: 'center', paddingVertical: 10, borderRadius: 14, backgroundColor: '#0F1114', borderWidth: 1, borderColor: C.border },
  cellA: { borderColor: C.lime, backgroundColor: C.limeDark }, cellT: { color: C.muted, fontSize: 10.5, fontWeight: '700', marginTop: 6 },
  banner: { position: 'absolute', top: 8, left: 8, backgroundColor: C.lime, borderRadius: 5, paddingHorizontal: 7, paddingVertical: 2 },
  bannerT: { color: '#0B0C0E', fontWeight: '900', fontSize: 10 },
  lab: { color: C.dim, fontFamily: MONO, fontSize: 11, letterSpacing: 1, marginBottom: 6, marginTop: 2 },
  corner: { width: 40, height: 36, borderRadius: 10, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center' },
  moreBox: { backgroundColor: '#0F1114', borderRadius: 14, borderWidth: 1, borderColor: C.border, padding: 14, marginTop: 10 },
  input: { backgroundColor: C.card, borderRadius: 12, borderWidth: 1, borderColor: C.border, color: C.text, padding: 12 },
});
