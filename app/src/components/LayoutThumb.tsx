import React from 'react';
import { View, StyleSheet } from 'react-native';
import { C } from '../theme';
import { PresetId, layoutPlan, defaultLayout, W, H } from '../layouts';

/** Tiny icon of a layout, drawn from the same plan the renderer uses. */
export default function LayoutThumb({ id, active }: { id: PresetId; active?: boolean }) {
  const pw = 34, ph = (pw * H) / W;
  const base = defaultLayout();
  const plan = layoutPlan({ ...base, preset: id, camBox: { x: 0, y: 0.6, w: 0.25, h: 0.4 }, gameBox: { x: 0.3, y: 0, w: 0.3, h: 1 } });
  const k = pw / W;
  return (
    <View style={{ width: pw, height: ph, borderRadius: 5, backgroundColor: '#07080A', overflow: 'hidden', borderWidth: 1, borderColor: active ? C.lime : '#2B2F36' }}>
      {plan.bg === 'blur' && <View style={[StyleSheet.absoluteFillObject, { backgroundColor: '#2A2D1A' }]} />}
      {plan.tiles.map((t, i) => (
        <View key={i} style={{
          position: 'absolute', left: t.x * k, top: t.y * k, width: t.w * k, height: t.h * k,
          backgroundColor: t.role === 'cam' ? C.pink : t.role === 'full' ? '#6B7F1E' : '#8DB31A',
          borderRadius: t.shape === 'circle' ? 99 : t.shape === 'round' ? 4 : 0,
        }} />
      ))}
    </View>
  );
}
