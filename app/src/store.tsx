import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Settings } from './types';
import { setBase } from './api';

export type Tab = 'create' | 'clips' | 'trending';
const DEFAULTS: Settings = { serverUrl: 'http://10.0.2.2:8000', geminiKey: '', hypeThreshold: 5, hypeEnabled: true };

interface Ctx {
  settings: Settings; setSettings: (s: Partial<Settings>) => void; ready: boolean;
  tab: Tab; setTab: (t: Tab) => void;
  pendingUrl: string | null; clipIt: (url: string) => void; consumeUrl: () => void;
  clipsVersion: number; bumpClips: () => void;
  savedNames: string[]; markSaved: (n: string) => void;
}
const AppCtx = createContext<Ctx>(null as any);
export const useApp = () => useContext(AppCtx);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [settings, setS] = useState<Settings>(DEFAULTS);
  const [ready, setReady] = useState(false);
  const [tab, setTab] = useState<Tab>('create');
  const [pendingUrl, setPending] = useState<string | null>(null);
  const [clipsVersion, setCV] = useState(0);
  const [savedNames, setSaved] = useState<string[]>([]);

  useEffect(() => {
    (async () => {
      try {
        const raw = await AsyncStorage.getItem('clipme.settings');
        const s = raw ? { ...DEFAULTS, ...JSON.parse(raw) } : DEFAULTS;
        setS(s); setBase(s.serverUrl);
        const sv = await AsyncStorage.getItem('clipme.saved');
        if (sv) setSaved(JSON.parse(sv));
      } catch { /* defaults */ }
      setReady(true);
    })();
  }, []);

  const setSettings = useCallback((p: Partial<Settings>) => {
    setS((prev) => {
      const n = { ...prev, ...p };
      if (p.serverUrl !== undefined) setBase(n.serverUrl);
      AsyncStorage.setItem('clipme.settings', JSON.stringify(n)).catch(() => {});
      return n;
    });
  }, []);
  const markSaved = useCallback((n: string) => setSaved((p) => {
    if (p.includes(n)) return p;
    const next = [...p, n]; AsyncStorage.setItem('clipme.saved', JSON.stringify(next)).catch(() => {}); return next;
  }), []);

  return (
    <AppCtx.Provider value={{
      settings, setSettings, ready, tab, setTab, pendingUrl,
      clipIt: (u) => { setPending(u); setTab('create'); }, consumeUrl: () => setPending(null),
      clipsVersion, bumpClips: () => setCV((v) => v + 1), savedNames, markSaved,
    }}>{children}</AppCtx.Provider>
  );
}
