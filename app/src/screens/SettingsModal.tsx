import React, { useState } from 'react';
import { Modal, View, Text, TextInput, StyleSheet, ScrollView } from 'react-native';
import { C, MONO } from '../theme';
import { Btn, Seg, Toggle } from '../components/ui';
import { useApp } from '../store';
import * as api from '../api';

export default function SettingsModal({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const { settings, setSettings } = useApp();
  const [status, setStatus] = useState('');
  const test = async () => {
    setStatus('Checking…'); api.setBase(settings.serverUrl);
    try { const h = await api.health(); setStatus(`✓ Connected · v${h.version} · captions ${h.captions ? 'available' : 'not installed'} · Gemini key on server: ${h.gemini_env ? 'yes' : 'no'}`); }
    catch (e: any) { setStatus('✗ ' + e.message); }
  };
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <ScrollView style={{ flex: 1, backgroundColor: C.bg }} contentContainerStyle={{ padding: 20, paddingTop: 56 }} keyboardShouldPersistTaps="handled">
        <Text style={{ color: C.text, fontSize: 24, fontWeight: '800', marginBottom: 18 }}>Settings</Text>
        <Text style={s.l}>SERVER ADDRESS</Text>
        <TextInput value={settings.serverUrl} onChangeText={(v) => setSettings({ serverUrl: v })} autoCapitalize="none" autoCorrect={false} style={s.in} placeholder="https://your-server:8000" placeholderTextColor={C.dim} />
        <Text style={s.h}>Android emulator: http://10.0.2.2:8000 · real phone: your computer/server's IP or domain.</Text>
        <Btn title="Test connection" variant="ghost" onPress={test} style={{ marginTop: 10 }} />
        {!!status && <Text style={[s.h, { color: status.startsWith('✓') ? C.lime : C.red }]}>{status}</Text>}

        <Text style={[s.l, { marginTop: 26 }]}>GEMINI API KEY (OPTIONAL)</Text>
        <TextInput value={settings.geminiKey} onChangeText={(v) => setSettings({ geminiKey: v.trim() })} autoCapitalize="none" autoCorrect={false} secureTextEntry style={s.in} placeholder="Leave empty to use the server's key" placeholderTextColor={C.dim} />
        <Text style={s.h}>Used by “Auto-find best clips”. Stored only on this phone and sent to your own server.</Text>

        <Text style={[s.l, { marginTop: 26 }]}>CHAT-HYPE SPIKE (% OF VIEWERS CHATTING)</Text>
        <Seg options={['3', '5', '8', '10'] as const} value={String(settings.hypeThreshold) as any} onChange={(v) => setSettings({ hypeThreshold: Number(v) })} labels={{ '3': '3%', '5': '5%', '8': '8%', '10': '10%' }} />
        <Text style={s.h}>5% means 400 chatters on an 8,000-viewer stream. Clips start 12 s before the spike and end 25 s after.</Text>
        <Btn title="Done" onPress={onClose} style={{ marginTop: 30 }} />
      </ScrollView>
    </Modal>
  );
}
const s = StyleSheet.create({
  l: { color: C.dim, fontFamily: MONO, fontSize: 11, letterSpacing: 1, marginBottom: 8 },
  in: { backgroundColor: C.card, borderRadius: 12, borderWidth: 1, borderColor: C.border, color: C.text, padding: 13 },
  h: { color: C.muted, fontSize: 12, marginTop: 8, lineHeight: 17 },
});
