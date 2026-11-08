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

import { registerHandler, startBridge } from './bridge/nativeHost';
import { registerPluginStorageHandlers } from './handlers/plugin-storage';
import { registerSpikeHandlers } from './handlers/spike-handlers';
import { normalizeChapterContent } from './plugins/helpers/chapterContent';
import {
  normalizePluginChapters,
  normalizePluginNovel,
} from './plugins/helpers/chapterPage';
import { flushPluginStorage } from './plugins/helpers/storage';
import {
  evaluatePlugin,
  getPlugin,
  initPlugin,
  removePlugin,
  resolvePluginUrl,
} from './plugins/pluginHost';
import { PluginContentType, PluginContentWarning } from './plugins/types';
import type { Filters, FilterToValues } from './plugins/types/filterTypes';

declare const global: {
  __TSUNDOKU_JS_READY__?: boolean;
  crypto: { getRandomValues<T extends Uint8Array>(array: T): T };
};

// M0 spike handlers. Task 8 replaces these with the real plugin surface.
registerSpikeHandlers();

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
  const site = plugin.site;
  return {
    id: plugin.id,
    name: plugin.name,
    version: plugin.version,
    isNekoriPlugin: plugin.isNekoriPlugin === true,
    minApiVersion: plugin.minApiVersion,
    site: typeof site === 'string' && site.trim() ? site : 'about:blank',
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

registerPluginStorageHandlers();

registerHandler('plugin.popularNovels', async args => {
  const {
    id,
    key,
    page,
    showLatestNovels = false,
    filters,
  } = args as {
    id: string;
    key?: string;
    page: number;
    showLatestNovels?: boolean;
    filters?: FilterToValues<Filters>;
  };
  const runtimeKey = key ?? id;
  const plugin = getPlugin(runtimeKey);
  try {
    const novels = await plugin.popularNovels(page, {
      showLatestNovels,
      filters: filters ?? plugin.filters,
    });
    return { novels };
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.searchNovels', async args => {
  const { id, key, query, page } = args as {
    id: string;
    key?: string;
    query: string;
    page: number;
  };
  const runtimeKey = key ?? id;
  try {
    return { novels: await getPlugin(runtimeKey).searchNovels(query, page) };
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.metadata', async args => {
  const { id, key } = args as { id: string; key?: string };
  const runtimeKey = key ?? id;
  try {
    const plugin = getPlugin(runtimeKey);
    return {
      site: plugin.site,
      webStorageUtilized: plugin.webStorageUtilized === true,
      imageRequestInit: plugin.imageRequestInit,
    };
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.filters', async args => {
  const { id, key } = args as { id: string; key?: string };
  const runtimeKey = key ?? id;
  try {
    return getPlugin(runtimeKey).filters ?? {};
  } finally {
    await flushPluginStorage(runtimeKey);
  }
});

registerHandler('plugin.settings', async args => {
  const { id, key } = args as { id: string; key?: string };
  const runtimeKey = key ?? id;
  try {
    return getPlugin(runtimeKey).pluginSettings ?? {};
  } finally {
    await flushPluginStorage(runtimeKey);
  }
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

registerHandler('plugin.parseChapter', async args => {
  const { id, key, path } = args as {
    id: string;
    key?: string;
    path: string;
  };
  const runtimeKey = key ?? id;
  try {
    const plugin = getPlugin(runtimeKey);
    return normalizeChapterContent(
      await plugin.parseChapter(path),
      plugin.isNekoriPlugin === true,
    );
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
