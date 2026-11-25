import React, { useCallback, useEffect, useState } from 'react';
import { View, Text, Image, TouchableOpacity, StyleSheet, ActivityIndicator, ScrollView } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C } from '../theme';
import { Pill } from '../components/ui';
import * as api from '../api';
import { useApp } from '../store';
import { TrendItem } from '../types';

const GAMES = ['All games', 'Just Chatting', 'Grand Theft Auto V', 'VALORANT', 'Fortnite', 'Call of Duty'];
const LANGS = ['Any', 'EN', 'PT', 'ES', 'FR', 'DE'];
const fmtV = (n: number) => (n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n));

export default function TrendingScreen() {
  const { clipIt } = useApp();
  const [platform, setPlatform] = useState<'all' | 'youtube' | 'twitch'>('all');
  const [game, setGame] = useState('All games');
  const [lang, setLang] = useState('Any');
  const [items, setItems] = useState<TrendItem[]>([]);
  const [demo, setDemo] = useState(false);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');
  const [updated, setUpdated] = useState(0);

  const load = useCallback(async () => {
    setLoading(true); setErr('');
    try {
      const r = await api.trending(platform, game === 'All games' ? '' : game, lang === 'Any' ? '' : lang);
      setItems(r.items); setDemo(r.demo); setUpdated(Date.now());
      if (!r.items.length && r.errors.length) setErr('Some sources failed: ' + r.errors.join(', '));
    } catch (e: any) { setErr(e.message); }
    setLoading(false);
  }, [platform, game, lang]);
  useEffect(() => { load(); }, [load]);

  return (
    <View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 2 }}>
        <View><Text style={s.h}>Live now</Text><Text style={s.sub}>Updated {updated ? Math.max(0, Math.round((Date.now() - updated) / 60000)) : 0} minutes ago</Text></View>
        <TouchableOpacity style={s.refresh} onPress={load}>{loading ? <ActivityIndicator color={C.text} /> : <Feather name="refresh-cw" size={18} color={C.text} />}</TouchableOpacity>
      </View>
      <View style={s.seg}>{(['all', 'youtube', 'twitch'] as const).map((p) => (
        <TouchableOpacity key={p} style={[s.segB, platform === p && { backgroundColor: C.lime }]} onPress={() => setPlatform(p)}>
          <Text style={{ color: platform === p ? '#0B0C0E' : C.text, fontWeight: '800' }}>{p === 'all' ? 'All' : p === 'youtube' ? 'YouTube' : 'Twitch'}</Text></TouchableOpacity>))}</View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginVertical: 10 }}>
        {GAMES.map((g) => <Pill key={g} title={g} active={g === game} onPress={() => setGame(g)} style={{ marginRight: 8 }} />)}
      </ScrollView>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: 12 }}>
        {LANGS.map((g) => <Pill key={g} title={g === 'Any' ? 'Any language' : g} active={g === lang} onPress={() => setLang(g)} style={{ marginRight: 8 }} />)}
      </ScrollView>
      {demo && <Text style={s.demo}>Demo data — add TWITCH_CLIENT_ID/SECRET or YOUTUBE_API_KEY on the server for the real live feed. You can still paste any link in Create.</Text>}
      {!!err && <Text style={{ color: C.red, marginBottom: 10 }}>{err}</Text>}
      {items.map((it) => (
        <View key={it.id} style={s.card}>
          <View style={s.thumb}>
            {it.thumbnail ? <Image source={{ uri: it.thumbnail }} style={{ width: '100%', height: '100%' }} /> : <Feather name="video" size={36} color={C.dim} />}
            <View style={s.live}><Text style={s.liveT}>● LIVE</Text></View>
            <View style={[s.plat, { backgroundColor: it.platform === 'youtube' ? C.yt : C.tw }]}><Text style={s.liveT}>{it.platform === 'youtube' ? 'YouTube' : 'Twitch'}</Text></View>
          </View>
          <View style={{ padding: 14 }}>
            <Text style={s.title} numberOfLines={2}>{it.title}</Text>
            <Text style={s.meta}>{it.channel}{it.game ? ` · ${it.game}` : ''}{it.lang ? ` · ${it.lang}` : ''}</Text>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 10 }}>
              <Text style={s.meta}>{fmtV(it.viewers)} watching</Text>
              <TouchableOpacity style={s.clip} onPress={() => clipIt(it.url)}><Text style={{ color: '#0B0C0E', fontWeight: '800' }}>Clip it →</Text></TouchableOpacity>
            </View>
          </View>
        </View>
      ))}
      {!items.length && !loading && !err && <Text style={s.meta}>No live streams found for these filters.</Text>}
    </View>
  );
}
const s = StyleSheet.create({
  h: { color: C.text, fontSize: 22, fontWeight: '800' }, sub: { color: C.muted, fontSize: 12, marginTop: 2 },
  refresh: { width: 40, height: 40, borderRadius: 20, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center' },
  seg: { flexDirection: 'row', backgroundColor: C.card, borderRadius: 14, padding: 4, marginTop: 14, borderWidth: 1, borderColor: C.border },
  segB: { flex: 1, alignItems: 'center', paddingVertical: 10, borderRadius: 11 },
  demo: { color: C.amber, fontSize: 12, marginBottom: 12, lineHeight: 17 },
  card: { backgroundColor: C.card, borderRadius: 16, borderWidth: 1, borderColor: C.border, marginBottom: 14, overflow: 'hidden' },
  thumb: { height: 190, backgroundColor: '#0A0B0D', alignItems: 'center', justifyContent: 'center' },
  live: { position: 'absolute', top: 10, left: 10, backgroundColor: '#FF4D3D', borderRadius: 6, paddingHorizontal: 7, paddingVertical: 2 },
  liveT: { color: '#fff', fontWeight: '800', fontSize: 11 }, plat: { position: 'absolute', top: 10, right: 10, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 },
  title: { color: C.text, fontWeight: '800', fontSize: 15, lineHeight: 20 }, meta: { color: C.muted, fontSize: 12, marginTop: 4 },
  clip: { backgroundColor: C.lime, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 9 },
});
