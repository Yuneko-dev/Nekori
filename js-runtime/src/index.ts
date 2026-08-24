/**
 * Entry point for the headless React Native runtime.
 *
 * There is no React component tree here and there never will be — the UI is Kotlin/Compose. This
 * bundle exists so plugin code can run on Hermes with npm libraries resolved by Metro.
 */

// MUST be first. React Native's polyfills — `setImmediate`, timers, `console`, error handling — are
// installed by setup-env, and `react-native/index.js` does NOT pull it in. A React Native app
// gets it because Metro's `getModulesRunBeforeMainModule` prepends it for the standard entry point;
// a bare headless bundle has to ask. Without it `setImmediate` is undefined, `startBridge()` throws
// during bundle evaluation, and the only symptom is that `ready()` never arrives.
import 'react-native/setup-env';
import './polyfills/secureRandom';
import './polyfills/nodeGlobals';
import './polyfills/textEncoding';

import { AppRegistry } from 'react-native/Libraries/ReactNative/AppRegistry';

import {
  registerHandler,
  registerTextHandler,
  startBridge,
} from './bridge/nativeHost';
import {
  normalizePluginChapters,
  normalizePluginNovel,
} from './plugins/helpers/chapterPage';
import {
  flushPluginStorage,
  getPluginStorageValue,
  setPluginStorageValue,
  setPluginWebStorage,
} from './plugins/helpers/storage';
import {
  evaluatePlugin,
  getPlugin,
  initPlugin,
  removePlugin,
  resolvePluginUrl,
} from './plugins/pluginHost';
import { PluginContentType, PluginContentWarning } from './plugins/types';

declare const global: {
  __TSUNDOKU_JS_READY__?: boolean;
  crypto: { getRandomValues<T extends Uint8Array>(array: T): T };
};

// M0 spike handlers. Task 8 replaces these with the real plugin surface.
registerHandler('sum', args => {
  const { a, b } = args as { a: number; b: number };
  return { result: a + b };
});

registerHandler('boom', args => {
  const { message } = args as { message: string };
  throw new Error(message);
});

// Never settles. Exists so the Kotlin side can be tested for cancellation and pending-map cleanup —
// a call that is abandoned must not leak its continuation.
registerHandler('never', () => new Promise<never>(() => {}));

registerHandler('secureRandom.sample', args => {
  const { size } = args as { size: number };
  const backing = new Uint8Array(size + 8);
  const first = new Uint8Array(backing.buffer, 4, size);
  const returned = global.crypto.getRandomValues(first);
  const second = new Uint8Array(size);
  global.crypto.getRandomValues(second);
  let floatError = '';
  let quotaError = '';
  try {
    global.crypto.getRandomValues(new Float32Array(1) as never);
  } catch (error) {
    floatError = error instanceof Error ? error.name : String(error);
  }
  try {
    global.crypto.getRandomValues(new Uint8Array(65_537));
  } catch (error) {
    quotaError = error instanceof Error ? error.name : String(error);
  }
  return {
    size,
    sameObject: returned === first,
    prefixUntouched: backing.slice(0, 4).every(value => value === 0),
    suffixUntouched: backing.slice(size + 4).every(value => value === 0),
    different: first.some((value, index) => value !== second[index]),
    floatError,
    quotaError,
  };
});

// Returns everything the host needs to identify a plugin, because the loaded code is the only
// authority on that. A repository index entry is a catalogue listing: it can be stale, or describe
// a version that was never the one written to disk.
registerHandler('plugin.load', async args => {
  const {
    id,
    code,
    key,
    validateId = true,
  } = args as {
    id: string;
    code: string;
    key?: string;
    validateId?: boolean;
  };
  const plugin = await initPlugin(id, code, key, validateId);
  return {
    id: plugin.id,
    name: plugin.name,
    version: plugin.version,
    site: plugin.site,
    contentWarning: plugin.contentWarning ?? PluginContentWarning.UNSPECIFIED,
    contentType: plugin.contentType ?? PluginContentType.NOVEL,
    webStorageUtilized: plugin.webStorageUtilized === true,
    imageRequestInit: plugin.imageRequestInit,
  };
});

registerHandler('plugin.eval', async args => {
  const { id, key, expression } = args as {
    id: string;
    key?: string;
    expression: string;
  };
  const runtimeKey = key ?? id;
  try {
    return await evaluatePlugin(runtimeKey, expression);
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.unload', async args => {
  const { id, key } = args as { id: string; key?: string };
  const runtimeKey = key ?? id;
  await flushPluginStorage(runtimeKey);
  removePlugin(runtimeKey);
  return null;
});

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

registerHandler('plugin.popularNovels', async args => {
  const { id, page } = args as { id: string; page: number };
  const plugin = getPlugin(id);
  const novels = await plugin.popularNovels(page, {
    showLatestNovels: false,
    // Not `undefined`. Plugins dereference their own declared filters — Báo Mới reads
    // `filters.page.value` — so the host is expected to materialize the plugin's defaults, each of
    // which already carries a `value`. M1's filter system has to do this properly; passing
    // `undefined` is what LNReader's type signature allows and what real plugins crash on.
    filters: plugin.filters,
  });
  return { novels };
});

registerHandler('plugin.searchNovels', async args => {
  const { id, query, page } = args as {
    id: string;
    query: string;
    page: number;
  };
  return { novels: await getPlugin(id).searchNovels(query, page) };
});

registerHandler('plugin.parseNovel', async args => {
  const { id, key, path } = args as {
    id: string;
    key?: string;
    path: string;
  };
  const runtimeKey = key ?? id;
  const plugin = getPlugin(runtimeKey);
  const paged = typeof plugin.parsePage === 'function';
  try {
    const sourceNovel = await plugin.parseNovel(path);
    const hasVolumePage =
      !paged &&
      Array.isArray(sourceNovel.chapters) &&
      sourceNovel.chapters.some(
        chapter =>
          typeof chapter.page === 'string' && chapter.page.trim().length > 0,
      );
    const novel = normalizePluginNovel(id, sourceNovel, paged);
    return {
      ...novel,
      __tsundokuLayout: paged ? 'PAGED' : hasVolumePage ? 'VOLUME' : 'FLAT',
    };
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.parsePage', async args => {
  const { id, key, path, page } = args as {
    id: string;
    key?: string;
    path: string;
    page: string;
  };
  const plugin = getPlugin(key ?? id);
  if (!plugin.parsePage) {
    throw new Error(`Plugin "${id}" does not implement parsePage`);
  }
  try {
    const sourcePage = await plugin.parsePage(path, page);
    return {
      chapters: normalizePluginChapters(id, sourcePage.chapters, 'parsePage', {
        pageOverride: page,
        validateNumeric: true,
      }),
    };
  } finally {
    await flushPluginStorage(key ?? id);
  }
});

registerHandler('plugin.resolveUrl', args => {
  const { id, key, path, isNovel } = args as {
    id: string;
    key?: string;
    path: string;
    isNovel?: boolean;
  };
  return { url: resolvePluginUrl(key ?? id, path, isNovel) };
});

registerTextHandler('plugin.parseChapter', async args => {
  const { id, key, path } = args as {
    id: string;
    key?: string;
    path: string;
  };
  const runtimeKey = key ?? id;
  try {
    return await getPlugin(runtimeKey).parseChapter(path);
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

AppRegistry.registerHeadlessTask('TsundokuJsRuntime', () => async () => {
  startBridge();
  global.__TSUNDOKU_JS_READY__ = true;
  console.log('[nekori] headless JS runtime started');
  await new Promise<never>(() => {});
});

console.log('[nekori] js runtime evaluated');

export {};
