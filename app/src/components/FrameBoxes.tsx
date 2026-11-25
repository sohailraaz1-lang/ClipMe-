import React, { useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, Image, PanResponder, StyleSheet, LayoutChangeEvent } from 'react-native';
import { C } from '../theme';
import { Box, clampBox } from '../layouts';

type Role = 'game' | 'cam';
interface Props {
  uri?: string; aspect: number; game: Box; cam: Box | null;
  showGame: boolean; showCam: boolean;
  targets: { game: number | null; cam: number | null };
  onChange: (role: Role, b: Box) => void;
}
const MIN = 0.05;

/** Frame with a lime GAME box and a pink CAM box. Drag the body to move; drag any corner to resize.
 *  When the layout needs an exact shape the box keeps that aspect, so what's inside the box is exactly what gets exported. */
export default function FrameBoxes({ uri, aspect, game, cam, showGame, showCam, targets, onChange }: Props) {
  const [w, setW] = useState(0);
  const h = w / aspect;
  const onLayout = (e: LayoutChangeEvent) => setW(e.nativeEvent.layout.width);
  return (
    <View onLayout={onLayout} style={{ width: '100%', height: w ? h : 180, borderRadius: 12, overflow: 'hidden', backgroundColor: '#000', borderWidth: 1, borderColor: C.border }}>
      {uri ? <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" /> : null}
      {w > 0 && showGame && <DragBox role="game" color={C.lime} box={game} W={w} H={h} aspect={aspect} target={targets.game} onChange={onChange} />}
      {w > 0 && showCam && cam && <DragBox role="cam" color={C.pink} box={cam} W={w} H={h} aspect={aspect} target={targets.cam} onChange={onChange} />}
    </View>
  );
}

function DragBox({ role, color, box, W, H, aspect, target, onChange }: {
  role: Role; color: string; box: Box; W: number; H: number; aspect: number; target: number | null;
  onChange: (r: Role, b: Box) => void;
}) {
  const live = useRef({ box, W, H, aspect, target, onChange });
  live.current = { box, W, H, aspect, target, onChange };
  const start = useRef<Box>(box);

  const body = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onPanResponderGrant: () => { start.current = live.current.box; },
    onPanResponderMove: (_e, g) => {
      const { W: ww, H: hh, onChange: oc } = live.current; const b = start.current;
      oc(role, clampBox({ ...b, x: b.x + g.dx / ww, y: b.y + g.dy / hh }));
    },
  }), [role]);

  const corner = (cx: 0 | 1, cy: 0 | 1) => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onPanResponderGrant: () => { start.current = live.current.box; },
    onPanResponderMove: (_e, g) => {
      const { W: ww, H: hh, aspect: A, target: T, onChange: oc } = live.current; const b = start.current;
      // anchor = opposite corner, pointer = dragged corner
      const ax = cx ? b.x : b.x + b.w, ay = cy ? b.y : b.y + b.h;
      const px = Math.min(1, Math.max(0, (cx ? b.x + b.w : b.x) + g.dx / ww));
      const py = Math.min(1, Math.max(0, (cy ? b.y + b.h : b.y) + g.dy / hh));
      const dirx = px >= ax ? 1 : -1, diry = py >= ay ? 1 : -1;
      const availX = dirx > 0 ? 1 - ax : ax, availY = diry > 0 ? 1 - ay : ay;
      let nw: number, nh: number;
      if (T) {
        const k = A / T; // normalized h = w * k keeps the pixel aspect exact
        nw = Math.min(Math.abs(px - ax), availX, availY / k); nw = Math.max(nw, MIN); nh = nw * k;
      } else {
        nw = Math.max(MIN, Math.min(Math.abs(px - ax), availX)); nh = Math.max(MIN, Math.min(Math.abs(py - ay), availY));
      }
      oc(role, clampBox({ x: dirx > 0 ? ax : ax - nw, y: diry > 0 ? ay : ay - nh, w: nw, h: nh }));
    },
  });
  const pans = useMemo(() => [corner(0, 0), corner(1, 0), corner(0, 1), corner(1, 1)], [role]);
  const pos: [number, number][] = [[0, 0], [1, 0], [0, 1], [1, 1]];

  return (
    <View {...body.panHandlers} style={{ position: 'absolute', left: box.x * W, top: box.y * H, width: box.w * W, height: box.h * H, borderWidth: 2, borderColor: color, backgroundColor: color + '14' }}>
      <View style={[st.tag, { backgroundColor: color }]}><Text style={st.tagT}>{role === 'game' ? 'GAME' : 'CAM'}</Text></View>
      {pans.map((p, i) => (
        <View key={i} {...p.panHandlers} hitSlop={{ top: 14, bottom: 14, left: 14, right: 14 }}
          style={[st.handle, { backgroundColor: color, left: pos[i][0] ? undefined : -8, right: pos[i][0] ? -8 : undefined, top: pos[i][1] ? undefined : -8, bottom: pos[i][1] ? -8 : undefined }]} />
      ))}
    </View>
  );
}
const st = StyleSheet.create({
  tag: { position: 'absolute', top: 4, left: 6, borderRadius: 4, paddingHorizontal: 6, paddingVertical: 1 },
  tagT: { color: '#0B0C0E', fontWeight: '900', fontSize: 10, letterSpacing: 0.5 },
  handle: { position: 'absolute', width: 16, height: 16, borderRadius: 8, borderWidth: 2, borderColor: '#0B0C0E' },
});
