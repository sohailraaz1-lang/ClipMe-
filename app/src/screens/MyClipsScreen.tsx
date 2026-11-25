import React, { useCallback, useEffect, useState } from 'react';
import { View, Text, Image, TouchableOpacity, StyleSheet, Modal, Share, Alert, ActivityIndicator } from 'react-native';
import { useVideoPlayer, VideoView } from 'expo-video';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { Btn } from '../components/ui';
import * as api from '../api';
import { useApp } from '../store';
import { ServerClip } from '../types';
import { saveToGallery } from '../gallery';

const ago = (t: number) => { const m = Math.round((Date.now() / 1000 - t) / 60); return m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} hours ago` : `${Math.round(m / 1440)} days ago`; };

function PlayerModal({ clip, onClose }: { clip: ServerClip | null; onClose: () => void }) {
  const player = useVideoPlayer(clip ? api.abs(clip.url)! : null, (p) => { p.loop = true; p.play(); });
  return (
    <Modal visible={!!clip} animationType="fade" onRequestClose={onClose}>
      <View style={{ flex: 1, backgroundColor: '#000' }}>
        {clip && <VideoView player={player} style={{ flex: 1 }} contentFit="contain" nativeControls />}
        <TouchableOpacity onPress={onClose} style={s.close}><Feather name="x" size={22} color="#fff" /></TouchableOpacity>
      </View>
    </Modal>
  );
}

export default function MyClipsScreen() {
  const { clipsVersion, savedNames, markSaved } = useApp();
  const [clips, setClips] = useState<ServerClip[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');
  const [play, setPlay] = useState<ServerClip | null>(null);
  const load = useCallback(async () => { setLoading(true); setErr(''); try { setClips((await api.listClips()).clips); } catch (e: any) { setErr(e.message); } setLoading(false); }, []);
  useEffect(() => { load(); }, [load, clipsVersion]);

  const remove = (c: ServerClip) => Alert.alert('Delete clip?', c.name, [{ text: 'Cancel' }, { text: 'Delete', style: 'destructive', onPress: async () => { await api.deleteClip(c.name).catch(() => {}); load(); } }]);
  const save = async (c: ServerClip) => { try { if (await saveToGallery(c.url, c.name)) { markSaved(c.name); Alert.alert('Saved', 'Added to your gallery (album ClipMe).'); } else Alert.alert('Permission needed', 'Allow gallery access to save clips.'); } catch (e: any) { Alert.alert('Couldn\'t save', e.message); } };

  return (
    <View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <Text style={{ color: C.muted }}>{clips.length} clip{clips.length === 1 ? '' : 's'}</Text>
        <TouchableOpacity style={s.open} onPress={load}>{loading ? <ActivityIndicator color={C.text} /> : <Text style={{ color: C.text, fontWeight: '700' }}>Refresh</Text>}</TouchableOpacity>
      </View>
      {!!err && <Text style={{ color: C.red }}>{err}</Text>}
      {!clips.length && !loading && !err && <Text style={{ color: C.muted }}>No clips yet. Make one in Create.</Text>}
      {clips.map((c) => (
        <View key={c.name} style={s.card}>
          <TouchableOpacity onPress={() => setPlay(c)}><Image source={{ uri: api.abs(c.thumb_url) }} style={s.thumb} /></TouchableOpacity>
          <View style={{ flex: 1, marginLeft: 12 }}>
            <Text style={{ color: C.text, fontFamily: MONO, fontSize: 12 }} numberOfLines={1}>{c.name.replace('.mp4', '')}</Text>
            <Text style={{ color: C.muted, fontSize: 12, marginTop: 4 }}>{Math.round(c.duration)} s · {(c.size / 1e6).toFixed(1)} MB · {ago(c.created)}</Text>
            {savedNames.includes(c.name) && <Text style={{ color: C.lime, fontSize: 11, marginTop: 2 }}>✓ In gallery</Text>}
            <View style={{ flexDirection: 'row', gap: 20, marginTop: 10 }}>
              <TouchableOpacity onPress={() => setPlay(c)}><Feather name="play" size={19} color={C.lime} /></TouchableOpacity>
              <TouchableOpacity onPress={() => Share.share({ message: api.abs(c.url)!, url: api.abs(c.url) })}><Feather name="share-2" size={19} color={C.text} /></TouchableOpacity>
              <TouchableOpacity onPress={() => save(c)}><Feather name="download" size={19} color={C.text} /></TouchableOpacity>
              <TouchableOpacity onPress={() => remove(c)}><Feather name="trash-2" size={19} color={C.muted} /></TouchableOpacity>
            </View>
          </View>
        </View>
      ))}
      <PlayerModal clip={play} onClose={() => setPlay(null)} />
    </View>
  );
}
const s = StyleSheet.create({
  card: { flexDirection: 'row', backgroundColor: C.card, borderRadius: 16, borderWidth: 1, borderColor: C.border, padding: 12, marginBottom: 12 },
  thumb: { width: 72, height: 128, borderRadius: 10, backgroundColor: '#0A0B0D' },
  open: { backgroundColor: C.card2, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 9, minWidth: 80, alignItems: 'center' },
  close: { position: 'absolute', top: 48, right: 20, width: 40, height: 40, borderRadius: 20, backgroundColor: '#0008', alignItems: 'center', justifyContent: 'center' },
});
