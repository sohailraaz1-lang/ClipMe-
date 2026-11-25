import React from 'react';
import { View, Text, TouchableOpacity, ActivityIndicator, StyleSheet } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { JobState } from '../types';
import { ProgressBar, useElapsed } from './ui';

const SUB: Record<number, string[]> = {};
export default function LoadCard({ title, steps, subs, job, onCancel, footer }: {
  title: string; steps: string[]; subs: string[]; job: JobState | null; onCancel: () => void; footer?: string;
}) {
  const el = useElapsed(true);
  const cur = job ? Math.min(job.step, steps.length - 1) : 0;
  return (
    <View style={s.card}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Text style={s.t}>{title}</Text>
        <TouchableOpacity onPress={onCancel} hitSlop={10}><Text style={{ color: C.red, fontWeight: '700' }}>Cancel</Text></TouchableOpacity>
      </View>
      {steps.map((st, i) => {
        const done = i < cur, active = i === cur;
        return (
          <View key={i} style={{ flexDirection: 'row', marginTop: 14, opacity: done || active ? 1 : 0.45 }}>
            <View style={[s.dot, done && { backgroundColor: C.lime, borderColor: C.lime }]}>
              {done ? <Feather name="check" size={13} color="#0B0C0E" /> : active ? <ActivityIndicator size="small" color={C.lime} /> : <Text style={{ color: C.muted, fontSize: 11 }}>{i + 1}</Text>}
            </View>
            <View style={{ flex: 1, marginLeft: 10 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                <Text style={{ color: C.text, fontWeight: '800', flex: 1 }}>{st}</Text>
                {active && <Text style={s.sec}>{el}s</Text>}
              </View>
              <Text style={s.sub}>{active && job?.detail ? job.detail : subs[i]}</Text>
            </View>
          </View>
        );
      })}
      <View style={{ marginTop: 16 }}><ProgressBar value={job ? job.progress : 0.04} /></View>
      {!!footer && <Text style={s.foot}>{footer}</Text>}
    </View>
  );
}
const s = StyleSheet.create({
  card: { backgroundColor: '#0F1114', borderRadius: 14, borderWidth: 1, borderColor: C.border, padding: 14, marginTop: 12 },
  t: { color: C.dim, fontFamily: MONO, fontSize: 11, letterSpacing: 1 },
  dot: { width: 24, height: 24, borderRadius: 12, borderWidth: 1, borderColor: C.border, alignItems: 'center', justifyContent: 'center' },
  sec: { color: C.muted, fontFamily: MONO, fontSize: 12 }, sub: { color: C.muted, fontSize: 12, marginTop: 2 },
  foot: { color: C.muted, fontSize: 12, marginTop: 10 },
});
