import React, { useEffect, useState } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Modal, ScrollView, Switch, ViewStyle, StyleProp, Pressable } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { C, MONO } from '../theme';
import { Tab } from '../store';

export const Card = ({ children, style }: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) =>
  <View style={[s.card, style]}>{children}</View>;

export const Label = ({ children, help }: { children: string; help?: () => void }) => (
  <View style={s.labelRow}>
    <Text style={s.label}>{children}</Text>
    {help && <TouchableOpacity onPress={help} hitSlop={10} style={s.helpBtn}><Feather name="help-circle" size={12} color={C.muted} /><Text style={s.help}>HELP</Text></TouchableOpacity>}
  </View>
);

export function Btn({ title, onPress, disabled, variant = 'primary', icon, style }: {
  title: string; onPress?: () => void; disabled?: boolean; variant?: 'primary' | 'ghost' | 'outline' | 'danger'; icon?: any; style?: StyleProp<ViewStyle>;
}) {
  const v = variant;
  return (
    <TouchableOpacity activeOpacity={0.8} disabled={disabled} onPress={onPress}
      style={[s.btn, v === 'primary' && s.btnP, v === 'ghost' && s.btnG, v === 'outline' && s.btnO, v === 'danger' && s.btnD, disabled && s.btnDis, style]}>
      {icon && <Feather name={icon} size={16} color={v === 'primary' && !disabled ? '#0B0C0E' : C.text} style={{ marginRight: 6 }} />}
      <Text style={[s.btnT, v === 'primary' && !disabled && { color: '#0B0C0E' }, v === 'danger' && { color: C.red }, disabled && { color: C.dim }]}>{title}</Text>
    </TouchableOpacity>
  );
}

export const Pill = ({ title, active, onPress, style }: { title: string; active?: boolean; onPress: () => void; style?: StyleProp<ViewStyle> }) => (
  <TouchableOpacity onPress={onPress} style={[s.pill, active && s.pillA, style]}>
    <Text style={[s.pillT, active && { color: '#0B0C0E' }]}>{title}</Text>
  </TouchableOpacity>
);

export function Seg<T extends string>({ options, value, onChange, labels }: { options: readonly T[]; value: T; onChange: (v: T) => void; labels?: Record<string, string> }) {
  return (
    <View style={{ flexDirection: 'row', gap: 8 }}>
      {options.map((o) => <Pill key={o} title={labels?.[o] ?? o} active={o === value} onPress={() => onChange(o)} style={{ flex: 1, alignItems: 'center' }} />)}
    </View>
  );
}

export const Toggle = (p: { value: boolean; onChange: (v: boolean) => void }) =>
  <Switch value={p.value} onValueChange={p.onChange} trackColor={{ false: '#2A2E35', true: '#6E8A12' }} thumbColor={p.value ? C.lime : '#9AA0A8'} />;

export function Header({ onSettings }: { onSettings: () => void }) {
  return (
    <View style={s.header}>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <View style={s.logoMark}><Feather name="scissors" size={15} color="#0B0C0E" /></View>
        <Text style={s.logo}>clip<Text style={{ color: C.lime }}>me</Text></Text>
      </View>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <View style={s.fmtBadge}><Text style={s.fmtT}>9:16 · 1080×1920</Text></View>
        <TouchableOpacity onPress={onSettings} style={s.avatar}><Feather name="settings" size={16} color={C.text} /></TouchableOpacity>
      </View>
    </View>
  );
}

export function Stepper({ step, onGo, maxStep }: { step: 1 | 2 | 3; onGo: (n: 1 | 2 | 3) => void; maxStep: number }) {
  const items: [1 | 2 | 3, string][] = [[1, 'Stream'], [2, 'Clips'], [3, 'Layout']];
  return (
    <View style={s.stepper}>
      {items.map(([n, label], i) => {
        const done = n < step, active = n === step, can = n <= maxStep;
        return (
          <React.Fragment key={n}>
            {i > 0 && <View style={[s.stepLine, n <= step && { backgroundColor: C.lime }]} />}
            <TouchableOpacity disabled={!can} onPress={() => onGo(n)} style={{ alignItems: 'center', width: 56 }}>
              <View style={[s.dot, (done || active) && { backgroundColor: C.lime, borderColor: C.lime }]}>
                {done ? <Feather name="check" size={13} color="#0B0C0E" /> : <Text style={[s.dotT, active && { color: '#0B0C0E' }]}>{n}</Text>}
              </View>
              <Text style={[s.stepT, (active || done) && { color: C.text }]}>{label}</Text>
            </TouchableOpacity>
          </React.Fragment>
        );
      })}
    </View>
  );
}

export function TabBar({ tab, onTab, bottom }: { tab: Tab; onTab: (t: Tab) => void; bottom: number }) {
  const items: [Tab, string, any][] = [['create', 'Create', 'plus-square'], ['clips', 'My clips', 'film'], ['trending', 'Trending', 'trending-up']];
  return (
    <View style={[s.tabs, { bottom: Math.max(bottom, 10) + 6 }]}>
      {items.map(([t, label, icon]) => (
        <TouchableOpacity key={t} onPress={() => onTab(t)} style={[s.tab, tab === t && s.tabA]}>
          <Feather name={icon} size={18} color={tab === t ? '#0B0C0E' : C.muted} />
          {tab === t && <Text style={s.tabT}>{label}</Text>}
        </TouchableOpacity>
      ))}
    </View>
  );
}

export function useElapsed(running: boolean) {
  const [t, setT] = useState(0);
  useEffect(() => {
    if (!running) { return; }
    setT(0);
    const i = setInterval(() => setT((x) => x + 1), 1000);
    return () => clearInterval(i);
  }, [running]);
  return t;
}

export function ProgressBar({ value }: { value: number }) {
  return <View style={s.bar}><View style={[s.barFill, { width: `${Math.max(3, Math.min(100, value * 100))}%` }]} /></View>;
}

export function HelpModal({ visible, onClose, title, children }: { visible: boolean; onClose: () => void; title: string; children: React.ReactNode }) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable style={s.modalBg} onPress={onClose}>
        <Pressable style={s.modal} onPress={() => {}}>
          <Text style={s.modalT}>{title}</Text>
          <ScrollView style={{ maxHeight: 420 }}>{children}</ScrollView>
          <TouchableOpacity onPress={onClose} style={{ alignSelf: 'flex-end', paddingTop: 14 }}><Text style={{ color: C.lime, fontWeight: '800', letterSpacing: 0.5 }}>GOT IT</Text></TouchableOpacity>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
export const H = ({ children }: { children: string }) => <Text style={{ color: C.lime, fontWeight: '800', marginTop: 12, marginBottom: 4 }}>{children}</Text>;
export const P = ({ children }: { children: React.ReactNode }) => <Text style={{ color: '#C9CDD2', lineHeight: 20, fontSize: 13 }}>{children}</Text>;

const s = StyleSheet.create({
  card: { backgroundColor: C.card, borderRadius: 16, borderWidth: 1, borderColor: C.border, padding: 16, marginBottom: 12 },
  labelRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  label: { color: C.dim, fontFamily: MONO, fontSize: 11, letterSpacing: 1 },
  helpBtn: { flexDirection: 'row', alignItems: 'center', gap: 4 }, help: { color: C.muted, fontFamily: MONO, fontSize: 10 },
  btn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderRadius: 14, paddingVertical: 15, paddingHorizontal: 14 },
  btnP: { backgroundColor: C.lime }, btnG: { backgroundColor: C.card2 }, btnO: { borderWidth: 1, borderColor: C.border, backgroundColor: C.card },
  btnD: { backgroundColor: '#2A1416' }, btnDis: { backgroundColor: C.card2, opacity: 0.8 },
  btnT: { color: C.text, fontWeight: '800', fontSize: 15 },
  pill: { backgroundColor: C.card2, borderRadius: 12, paddingVertical: 9, paddingHorizontal: 14 },
  pillA: { backgroundColor: C.lime }, pillT: { color: C.text, fontWeight: '700', fontSize: 13 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 10 },
  logoMark: { width: 26, height: 26, borderRadius: 7, backgroundColor: C.lime, alignItems: 'center', justifyContent: 'center', marginRight: 8 },
  logo: { color: C.text, fontSize: 21, fontWeight: '800' },
  fmtBadge: { borderWidth: 1, borderColor: C.border, borderRadius: 12, paddingHorizontal: 9, paddingVertical: 5 },
  fmtT: { color: C.muted, fontFamily: MONO, fontSize: 10 },
  avatar: { width: 34, height: 34, borderRadius: 17, backgroundColor: C.card2, borderWidth: 1, borderColor: C.border, alignItems: 'center', justifyContent: 'center' },
  stepper: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'center', paddingVertical: 8, paddingHorizontal: 20 },
  stepLine: { flex: 1, height: 2, backgroundColor: C.border, marginTop: 13 },
  dot: { width: 28, height: 28, borderRadius: 14, borderWidth: 1, borderColor: C.border, backgroundColor: C.card, alignItems: 'center', justifyContent: 'center' },
  dotT: { color: C.muted, fontWeight: '800', fontSize: 12 }, stepT: { color: C.dim, fontSize: 11, marginTop: 4, fontWeight: '600' },
  tabs: { position: 'absolute', left: 16, right: 16, flexDirection: 'row', backgroundColor: '#15171BEE', borderRadius: 30, padding: 6, borderWidth: 1, borderColor: C.border, justifyContent: 'space-between' },
  tab: { flex: 1, height: 46, borderRadius: 24, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 6 },
  tabA: { backgroundColor: C.lime, flex: 1.6 }, tabT: { color: '#0B0C0E', fontWeight: '800' },
  bar: { height: 5, backgroundColor: '#23272D', borderRadius: 3, overflow: 'hidden' }, barFill: { height: 5, backgroundColor: C.lime, borderRadius: 3 },
  modalBg: { flex: 1, backgroundColor: '#000A', justifyContent: 'center', padding: 24 },
  modal: { backgroundColor: '#15171B', borderRadius: 18, padding: 20, borderWidth: 1, borderColor: C.border },
  modalT: { color: C.text, fontSize: 18, fontWeight: '800', marginBottom: 6 },
});
