import React, { useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { C } from '../theme';
import { Card, Label, HelpModal, H, P } from '../components/ui';
import LoadCard from '../components/LoadCard';
import * as api from '../api';
import { JobState, StreamMeta } from '../types';

export default function StreamStep({ initialUrl, onReady }: { initialUrl?: string | null; onReady: (m: StreamMeta) => void }) {
  const [url, setUrl] = useState(initialUrl ?? '');
  const [job, setJob] = useState<JobState | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [help, setHelp] = useState(false);
  const handle = useRef<ReturnType<typeof api.runJob> | null>(null);

  const start = async (u: string) => {
    const link = u.trim();
    if (!/^https?:\/\/(www\.|m\.|music\.)?(youtube\.com|youtu\.be|twitch\.tv)\//i.test(link)) { setErr('Paste a YouTube or Twitch link.'); return; }
    setErr(''); setBusy(true); setJob(null);
    const h = api.runJob<StreamMeta>(() => api.resolve(link), setJob);
    handle.current = h;
    try { const meta = await h.promise; setBusy(false); onReady(meta); }
    catch (e: any) { setBusy(false); if (e.message !== 'cancelled') setErr(e.message); }
  };
  useEffect(() => { if (initialUrl) { setUrl(initialUrl); start(initialUrl); } }, [initialUrl]);

  const paste = async () => { const t = await Clipboard.getStringAsync(); if (t) { setUrl(t); start(t); } };
  const checking = busy && !job?.step ? 'Checking link…' : busy ? 'Almost there…' : '';

  return (
    <Card>
      <Label help={() => setHelp(true)}>01 · PASTE A STREAM LINK</Label>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <TextInput value={url} onChangeText={setUrl} placeholder="Paste a YouTube or Twitch link" placeholderTextColor={C.dim}
          autoCapitalize="none" autoCorrect={false} keyboardType="url" returnKeyType="go" onSubmitEditing={() => start(url)}
          style={s.input} editable={!busy} />
        <TouchableOpacity style={s.paste} onPress={paste} disabled={busy}><Text style={{ color: C.text, fontWeight: '700' }}>Paste</Text></TouchableOpacity>
      </View>
      <Text style={s.hint}>{checking || 'Live or ended streams, uploaded videos — YouTube or Twitch'}</Text>
      {!!err && <Text style={s.err}>{err}</Text>}
      {!busy && !!url.trim() && <TouchableOpacity style={s.go} onPress={() => start(url)}><Text style={{ color: '#0B0C0E', fontWeight: '800' }}>Check link</Text></TouchableOpacity>}
      {busy && (
        <LoadCard title="LOADING YOUR STREAM" job={job}
          steps={['Checking the link…', 'Getting a frame from the stream…']}
          subs={["Reading the stream's details", 'So you can place the game and cam boxes']}
          footer="Usually 10–30 s. The options open as soon as your stream is ready."
          onCancel={() => { handle.current?.cancel(); setBusy(false); }} />
      )}
      <HelpModal visible={help} onClose={() => setHelp(false)} title="Which links work?">
        <P>Paste a link to a live stream, an ended live stream (VOD) or a regular uploaded video from YouTube or Twitch. We only read the details and one frame — nothing big is downloaded yet.</P>
        <H>Tip</H><P>Share → Copy link in the YouTube or Twitch app, then tap Paste.</P>
      </HelpModal>
    </Card>
  );
}
const s = StyleSheet.create({
  input: { flex: 1, backgroundColor: '#0F1114', borderRadius: 12, borderWidth: 1, borderColor: C.border, color: C.text, paddingHorizontal: 12, paddingVertical: 12, fontSize: 14 },
  paste: { backgroundColor: C.card2, borderRadius: 12, paddingHorizontal: 16, justifyContent: 'center' },
  hint: { color: C.muted, fontSize: 12, marginTop: 8 }, err: { color: C.red, marginTop: 8, fontSize: 13, fontWeight: '600' },
  go: { backgroundColor: C.lime, borderRadius: 12, alignItems: 'center', paddingVertical: 13, marginTop: 12 },
});
