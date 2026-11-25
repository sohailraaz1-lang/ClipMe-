import * as Notifications from 'expo-notifications';
import Constants from 'expo-constants';
import { Platform } from 'react-native';

Notifications.setNotificationHandler({
  handleNotification: async () => ({ shouldShowAlert: true, shouldPlaySound: false, shouldSetBadge: false }),
});

/** Asks for permission (shown the first time "Clip it" is pressed) and returns an Expo push token if available. */
export async function setupNotifications(): Promise<string | undefined> {
  try {
    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('clips', { name: 'Clips', importance: Notifications.AndroidImportance.HIGH });
    }
    const cur = await Notifications.getPermissionsAsync();
    const ok = cur.granted || (await Notifications.requestPermissionsAsync()).granted;
    if (!ok) return undefined;
    const projectId = (Constants.expoConfig as any)?.extra?.eas?.projectId ?? (Constants as any).easConfig?.projectId;
    if (!projectId) return undefined; // push needs an EAS project; local notifications still work
    return (await Notifications.getExpoPushTokenAsync({ projectId })).data;
  } catch { return undefined; }
}
export const notifyLocal = (title: string, body: string) =>
  Notifications.scheduleNotificationAsync({ content: { title, body }, trigger: null }).catch(() => {});
