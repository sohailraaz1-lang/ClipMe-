import * as FileSystem from 'expo-file-system';
import * as MediaLibrary from 'expo-media-library';
import { abs } from './api';

/** Downloads a finished clip from the server and saves it into the phone gallery (album "ClipMe"). */
export async function saveToGallery(urlPath: string, name: string): Promise<boolean> {
  const perm = await MediaLibrary.requestPermissionsAsync(true);
  if (!perm.granted) return false;
  const dest = FileSystem.cacheDirectory + name;
  const res = await FileSystem.downloadAsync(abs(urlPath)!, dest);
  if (res.status !== 200) return false;
  const asset = await MediaLibrary.createAssetAsync(res.uri);
  try {
    const album = await MediaLibrary.getAlbumAsync('ClipMe');
    if (album) await MediaLibrary.addAssetsToAlbumAsync([asset], album, false);
    else await MediaLibrary.createAlbumAsync('ClipMe', asset, false);
  } catch { /* the asset is already in the gallery */ }
  FileSystem.deleteAsync(res.uri, { idempotent: true }).catch(() => {});
  return true;
}
