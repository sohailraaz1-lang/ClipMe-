import { Platform } from 'react-native';
export const C = {
  bg: '#0B0C0E', card: '#131519', card2: '#1A1D22', border: '#23272D', text: '#F2F4F5', muted: '#8A9099',
  dim: '#5B616A', lime: '#C6FF1A', limeDark: '#1F2A08', pink: '#FF4DA6', red: '#FF4D4D', amber: '#FFB020',
  yt: '#FF0033', tw: '#9146FF',
};
export const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' }) as string;
export const R = { card: 16, pill: 12 };
