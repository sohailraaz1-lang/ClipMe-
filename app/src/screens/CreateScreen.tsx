import React, { useEffect, useRef, useState } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Modal, ScrollView } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { Btn, Card, Label, ProgressBar, Stepper, useElapsed } from '../components/ui';
import StreamStep from './StreamStep';
import ClipsStep from './ClipsStep';
import LayoutStep, { RenderCfg } from './LayoutStep';
import * as api from '../api';
import { useApp } from '../store';
import { setupNotifications, notifyLocal } from '../notify';
import { saveToGallery } from '../gallery';
import { defaultLayout } from '../layouts';
import { ClipItem, JobState, ServerClip, StreamMeta, WindowInfo } from '../types';
import { fmtHMS } from '../time';

const initialCfg = (): RenderCfg => ({ spec: defaultLayout(), captions: false, captionStyle: 'bold', font: 'bold', hook: '', hookPos: 'top', quality: 'balanced', fps: 30 });

export default function CreateScreen() {
  const { pendingUrl, consumeUrl, setTab, bumpClips, markSaved } = useApp();
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [meta, setMeta] = useState<StreamMeta | null>(null);
  const [clips, setClips] = useState<ClipItem[]>([]);
  const [win, setWin] = useState<WindowInfo | null>(null);
  const [cfg, setCfg] = useState<RenderCfg>(initialCfg);
  const [job, setJob] = useState<JobState | null>(null);
  const [rendering, setRendering] = useState(false);
  const [err, setErr] = useState('');
  const [logOpen, setLogOpen] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const handle = useRef<ReturnType<typeof api.runJob> | null>(null);
  const elapsed = useElapsed(rendering);
  const maxStep = !meta ? 1 : clips.length ? 3 : 2;
  const url = pendingUrl;
  useEffect(() => { if (pendingUrl) { setStep(1); setMeta(null); setClips([]); setWin(null); } }, [pendingUrl]);

  const onReady = (m: StreamMeta) => { consumeUrl(); setMeta(m); setClips([]); setWin(null); setStep(2); };

  const startRender = async () => {
    if (!meta || !clips.length) return;
    setErr(''); setRendering(true); setJob(null);
    const token = await setupNotifications();
    const { spec } = cfg;
    const body = {
      url: meta.url, is_live: meta.is_live, clips: clips.map((c) => ({ start: c.start, end: c.end })),
      layout: { preset: spec.preset, split_ratio: spec.splitRatio, cam_corner: spec.camCorner, cam_size: spec.camSize, game_box: spec.gameBox, cam_box: spec.camBox },
      captions: cfg.captions, caption_style: cfg.captionStyle, font: cfg.font, hook_text: cfg.hook, hook_position: cfg.hookPos,
      quality: cfg.quality, fps: cfg.fps, push_token: token,
    };
    const h = api.runJob<{ clips: ServerClip[] }>(() => api.render(body), setJob);
    handle.current = h;
    try {
      const res = await h.promise;
      let saved = 0;
      for (const c of res.clips) { try { if (await saveToGallery(c.url, c.name)) { saved++; markSaved(c.name); } } catch { /* keep going */ } }
      notifyLocal('Clips ready', `${res.clips.length} clip${res.clips.length === 1 ? '' : 's'} ${saved ? 'saved to your gallery' : 'ready in My clips'}`);
      setRendering(false); bumpClips(); setTab('clips');
    } catch (e: any) { setRendering(false); if (e.message !== 'cancelled') setErr(e.message); }
  };
  const openLog = async () => { const id = handle.current?.jobId(); if (id) { try { setLog((await api.jobLog(id)).log); } catch { setLog(['No log yet.']); } } setLogOpen(true); };

  return (
    <View>
      <Stepper step={step} maxStep={maxStep} onGo={(n) => !rendering && setStep(n)} />
      {meta && step > 1 && (
        <TouchableOpacity style={s.row} onPress={() => !rendering && setStep(1)}>
          <Text style={s.rowL}>STREAM</Text>
          <View style={[s.live, !meta.is_live && { backgroundColor: '#2A2E35' }]}><Text style={s.liveT}>{meta.is_live ? '● LIVE' : 'VOD'}</Text></View>
          <Text style={s.rowT} numberOfLines={1}>{meta.title}</Text><Feather name="edit-2" size={14} color={C.muted} />
        </TouchableOpacity>
      )}
      {step === 3 && (
        <TouchableOpacity style={s.row} onPress={() => !rendering && setStep(2)}>
          <Text style={s.rowL}>CLIPS</Text>
          <Text style={s.rowT}>{clips.length} clip{clips.length === 1 ? '' : 's'} · {fmtHMS(clips.reduce((a, c) => a + c.end - c.start, 0))} total</Text><Feather name="edit-2" size={14} color={C.muted} />
        </TouchableOpacity>
      )}

      {step === 1 && <StreamStep initialUrl={url} onReady={onReady} />}
      {step === 2 && meta && <ClipsStep meta={meta} clips={clips} setClips={setClips} win={win} setWin={setWin} onNext={() => setStep(3)} />}
      {step === 3 && meta && <LayoutStep cfg={cfg} setCfg={setCfg} clips={clips} busy={rendering} onRender={startRender} />}

      {!!err && <Text style={{ color: C.red, textAlign: 'center', marginTop: 10, fontWeight: '700' }}>{err}</Text>}
      {rendering && (
        <Card style={{ marginTop: 14 }}>
          <Label>{`MAKING CLIPS · ${job ? Math.min(job.step + 1, clips.length) : 1} / ${clips.length}`}</Label>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text style={{ color: C.text, fontWeight: '800', flex: 1 }}>{job?.message || 'Starting…'}</Text>
            <TouchableOpacity onPress={() => handle.current?.cancel()}><Text style={{ color: C.red, fontWeight: '700' }}>Cancel</Text></TouchableOpacity>
          </View>
          <Text style={s.det}>{job?.detail || 'Preparing…'}</Text>
          <Text style={s.det}>{elapsed}s elapsed</Text>
          <Text style={[s.det, { color: C.text }]}>You can leave the app — we'll notify you when clips are ready.</Text>
          <View style={{ marginTop: 12 }}><ProgressBar value={job?.progress ?? 0.03} /></View>
          <TouchableOpacity onPress={openLog}><Text style={{ color: C.muted, textAlign: 'center', marginTop: 12 }}>View log</Text></TouchableOpacity>
        </Card>
      )}
      <Modal visible={logOpen} animationType="slide" onRequestClose={() => setLogOpen(false)}>
        <View style={{ flex: 1, backgroundColor: C.bg, padding: 16, paddingTop: 50 }}>
          <ScrollView>{log.map((l, i) => <Text key={i} style={{ color: C.muted, fontFamily: MONO, fontSize: 11, marginBottom: 4 }}>{l}</Text>)}</ScrollView>
          <Btn title="Close" onPress={() => setLogOpen(false)} />
        </View>
      </Modal>
    </View>
  );
}
const s = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: C.card, borderWidth: 1, borderColor: C.border, borderRadius: 14, padding: 12, marginBottom: 10 },
  rowL: { color: C.dim, fontFamily: MONO, fontSize: 11 }, rowT: { color: C.text, fontFamily: MONO, fontSize: 13, flex: 1 },
  live: { backgroundColor: '#FF4D3D', borderRadius: 6, paddingHorizontal: 7, paddingVertical: 2 }, liveT: { color: '#fff', fontWeight: '800', fontSize: 11 },
  det: { color: C.muted, fontSize: 12, marginTop: 6 },
});
