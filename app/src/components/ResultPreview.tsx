import React from 'react';
import { View, Image, Text } from 'react-native';
import { C } from '../theme';
import { LayoutSpec, layoutPlan, W, H, Tile } from '../layouts';

/** "RESULT" thumbnail: renders the exact tiles the server will render (same plan, same cover-crop). */
export default function ResultPreview({ uri, aspect, spec, width = 120, hook }: {
  uri?: string; aspect: number; spec: LayoutSpec; width?: number; hook?: string;
}) {
  const plan = layoutPlan(spec, aspect);
  const k = width / W;
  const ph = H * k;
  const cover = (src: { x: number; y: number; w: number; h: number }, tw: number, th: number) => {
    const cw = src.w * aspect, ch = src.h;          // crop size in "frame units" (frame = aspect x 1)
    const sc = Math.max(tw / cw, th / ch);
    return { width: aspect * sc, height: sc, left: -(src.x * aspect * sc) - (cw * sc - tw) / 2, top: -(src.y * sc) - (ch * sc - th) / 2 };
  };
  const renderTile = (t: Tile, i: number) => {
    const tw = t.w * k, th = t.h * k;
    return (
      <View key={i} style={{ position: 'absolute', left: t.x * k, top: t.y * k, width: tw, height: th, overflow: 'hidden',
        borderRadius: t.shape === 'circle' ? tw / 2 : t.shape === 'round' ? t.radius * k : 0, backgroundColor: '#111' }}>
        {uri ? <Image source={{ uri }} style={{ position: 'absolute', ...cover(t.src, tw, th) }} /> : null}
      </View>
    );
  };
  return (
    <View style={{ width, height: ph, borderRadius: 8, overflow: 'hidden', backgroundColor: '#000', borderWidth: 1, borderColor: C.border }}>
      {plan.bg === 'blur' && uri ? <>
        <Image source={{ uri }} blurRadius={14} style={{ position: 'absolute', ...cover({ x: 0, y: 0, w: 1, h: 1 }, width, ph) }} />
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#0006' }} />
      </> : null}
      {plan.tiles.map(renderTile)}
      {!!hook && <View style={{ position: 'absolute', top: 230 * k, left: 6, right: 6, alignItems: 'center' }}>
        <Text style={{ color: '#fff', fontWeight: '900', fontSize: Math.max(7, 68 * k), textAlign: 'center', textShadowColor: '#000', textShadowRadius: 3 }}>{hook}</Text></View>}
    </View>
  );
}
