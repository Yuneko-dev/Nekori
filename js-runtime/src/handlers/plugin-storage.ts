import { registerHandler } from '../bridge/nativeHost';
import {
  getPluginStorageValue,
  setPluginStorageValue,
  setPluginWebStorage,
} from '../plugins/helpers/storage';

export function registerPluginStorageHandlers(): void {
  registerHandler('plugin.storageGet', args => {
    const { id, key, storageKey } = args as {
      id: string;
      key?: string;
      storageKey: string;
    };
    return getPluginStorageValue(key ?? id, storageKey);
  });

  registerHandler('plugin.storageSet', async args => {
    const { id, key, storageKey, value } = args as {
      id: string;
      key?: string;
      storageKey: string;
      value: unknown;
    };
    await setPluginStorageValue(key ?? id, storageKey, value);
    return null;
  });

  registerHandler('plugin.webStorageSet', async args => {
    const { id, key, localStorage, sessionStorage } = args as {
      id: string;
      key?: string;
      localStorage: Record<string, string>;
      sessionStorage: Record<string, string>;
    };
    await setPluginWebStorage(
      key ?? id,
      localStorage ?? {},
      sessionStorage ?? {},
    );
    return null;
  });
}
