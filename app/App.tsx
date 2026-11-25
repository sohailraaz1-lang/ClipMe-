import React, { useState } from 'react';
import { View, ScrollView, KeyboardAvoidingView, Platform } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { C } from './src/theme';
import { AppProvider, useApp } from './src/store';
import { Header, TabBar } from './src/components/ui';
import CreateScreen from './src/screens/CreateScreen';
import MyClipsScreen from './src/screens/MyClipsScreen';
import TrendingScreen from './src/screens/TrendingScreen';
import SettingsModal from './src/screens/SettingsModal';

function Shell() {
  const insets = useSafeAreaInsets();
  const { tab, setTab, ready } = useApp();
  const [settings, setSettings] = useState(false);
  if (!ready) return <View style={{ flex: 1, backgroundColor: C.bg }} />;
  // All three screens stay mounted so a half-finished clip session survives tab switches.
  const page = (t: typeof tab, el: React.ReactNode) => (
    <ScrollView style={{ display: tab === t ? 'flex' : 'none' }} contentContainerStyle={{ padding: 16, paddingBottom: 130 }} keyboardShouldPersistTaps="handled">{el}</ScrollView>
  );
  return (
    <View style={{ flex: 1, backgroundColor: C.bg, paddingTop: insets.top }}>
      <StatusBar style="light" />
      <Header onSettings={() => setSettings(true)} />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        {page('create', <CreateScreen />)}
        {page('clips', <MyClipsScreen />)}
        {page('trending', <TrendingScreen />)}
      </KeyboardAvoidingView>
      <TabBar tab={tab} onTab={setTab} bottom={insets.bottom} />
      <SettingsModal visible={settings} onClose={() => setSettings(false)} />
    </View>
  );
}
export default function App() {
  return <SafeAreaProvider><AppProvider><Shell /></AppProvider></SafeAreaProvider>;
}
