import {
  aeskw,
  aeskwp,
  aessiv,
  cbc,
  cfb,
  cmac,
  ctr,
  ecb,
  gcm,
  gcmsiv,
} from '@noble/ciphers/aes.js';
import { bytesToUtf8, utf8ToBytes } from '@noble/ciphers/utils.js';
import { Buffer } from 'buffer';
import { load } from 'cheerio';
import NodeCrypto from 'crypto-browserify';
import dayjs from 'dayjs';
import {
  decode as decodeHtmlEntities,
  encode as encodeHtmlEntities,
} from 'html-entities';
import { Parser } from 'htmlparser2';
import { decode, encode } from 'urlencode';

import {
  solveCloudflareAPI,
  solveCloudflareTurnstileAPI,
} from './helpers/cloudflareStore';
import { defaultCover } from './helpers/constants';
import CookieManager from './helpers/cookie';
import { fetchApi, fetchFile, fetchProto, fetchText } from './helpers/fetch';
import { isUrlAbsolute } from './helpers/isAbsoluteUrl';
import { getUserAgent } from './helpers/nativeHost';
import {
  hydratePluginStorage,
  removePluginStorageContext,
  storageModule,
} from './helpers/storage';
import {
  NodeHtmlMarkdown,
  PostProcessResult,
  TranslatorCollection,
} from './modules/node-html-markdown';
import {
  NovelStatus,
  Plugin,
  PluginContentType,
  PluginContentWarning,
} from './types';
import { FilterTypes } from './types/filterTypes';

const contentWarningValues = new Set<number>([
  PluginContentWarning.UNSPECIFIED,
  PluginContentWarning.SAFE,
  PluginContentWarning.MIXED,
  PluginContentWarning.NSFW,
]);

const contentTypeValues = new Set<string>(Object.values(PluginContentType));

const normalizePluginContentWarning = (
  contentWarning: unknown,
): PluginContentWarning => {
  return typeof contentWarning === 'number' &&
    contentWarningValues.has(contentWarning)
    ? contentWarning
    : PluginContentWarning.UNSPECIFIED;
};

const normalizePluginContentType = (
  contentType: unknown,
): PluginContentType => {
  return typeof contentType === 'string' && contentTypeValues.has(contentType)
    ? (contentType as PluginContentType)
    : PluginContentType.NOVEL;
};

const normalizeLoadedPluginMetadata = <T extends Plugin>(plugin: T): T => {
  plugin.contentWarning = normalizePluginContentWarning(plugin.contentWarning);
  plugin.contentType = normalizePluginContentType(plugin.contentType);
  return plugin;
};

/**
 * Loads and runs LNReader plugins.
 *
 * The evaluation step is LNReader's, verbatim in shape
 * (`lnreader/src/plugins/pluginManager.ts:152-196`): a plugin is a CommonJS module handed a `require`
 * closed over its own id, evaluated with the `Function` constructor. **Whether Hermes permits that at
 * all is the question this spike exists to answer** — Hermes ships without `eval` in some
 * configurations, and every LNReader plugin is fetched as source at runtime.
 */
const packages: Record<string, unknown> = {
  '@libs/novelStatus': { NovelStatus },
  '@libs/filterInputs': { FilterTypes },
  '@libs/defaultCover': { defaultCover },
  '@libs/fetch': { fetchApi, fetchFile, fetchProto, fetchText },
  '@libs/isAbsoluteUrl': { isUrlAbsolute },
  '@libs/aes': { ctr, ecb, cbc, cfb, gcm, gcmsiv, aeskw, aeskwp, cmac, aessiv },
  htmlparser2: { Parser },
  cheerio: { load },
  dayjs: dayjs,
  urlencode: { encode, decode },
  'node-html-markdown': {
    NodeHtmlMarkdown,
    PostProcessResult,
    TranslatorCollection,
  },
  '@libs/utils': {
    utf8ToBytes,
    bytesToUtf8,
    Buffer,
    encodeHtmlEntities,
    decodeHtmlEntities,
    NodeCrypto,
    getUserAgent,
  },
  '@libs/cookie': CookieManager,
  '@libs/pluginMetadata': {
    ContentWarning: PluginContentWarning,
    ContentType: PluginContentType,
  },
  '@libs/webview': {
    solveCloudflare: solveCloudflareAPI,
    solveCloudflareTurnstile: solveCloudflareTurnstileAPI,
  },
};

const plugins = new Map<string, Plugin>();

function makeRequire(runtimeKey: string): (name: string) => unknown {
  return (name: string) => {
    if (name === '@libs/storage') {
      return storageModule(runtimeKey);
    }
    const module = packages[name];
    if (module === undefined) {
      // Never return {}. A missing module has to fail here, loudly, rather than turning into wrong
      // data three call frames later — the one rule the whole plugin layer is built on.
      throw new Error(`Plugin required "${name}", which is not implemented`);
    }
    return module;
  };
}

export async function initPlugin(
  pluginId: string,
  rawCode: string,
  runtimeKey: string = pluginId,
): Promise<Plugin> {
  await hydratePluginStorage(pluginId, runtimeKey);
  try {
    const sourceUrl = `lnreader-plugin://${encodeURIComponent(runtimeKey)}.js`;
    const plugin = Function(
      'require',
      'module',
      `const exports = module.exports = {};
      ${rawCode};
      return exports.default;
      //# sourceURL=${sourceUrl}`,
    )(makeRequire(runtimeKey), {}) as Plugin | undefined;

    if (!plugin) {
      throw new Error(`Plugin "${pluginId}" evaluated but exported no default`);
    }
    if (plugin.id !== pluginId) {
      throw new Error(
        `Plugin id mismatch: expected "${pluginId}", got "${plugin.id}"`,
      );
    }

    if (!plugin.imageRequestInit) {
      plugin.imageRequestInit = {
        headers: { 'User-Agent': getUserAgent() },
      };
    } else {
      if (!plugin.imageRequestInit.headers) {
        plugin.imageRequestInit.headers = {};
      }

      const hasUserAgent = Object.keys(plugin.imageRequestInit.headers).some(
        header => header.toLowerCase() === 'user-agent',
      );

      if (!hasUserAgent) {
        plugin.imageRequestInit.headers['User-Agent'] = getUserAgent();
      }
    }

    const pluginNormalize = normalizeLoadedPluginMetadata(plugin);

    plugins.set(runtimeKey, pluginNormalize);
    return pluginNormalize;
  } catch (error) {
    removePluginStorageContext(runtimeKey);
    throw error;
  }
}

export function getPlugin(runtimeKey: string): Plugin {
  const plugin = plugins.get(runtimeKey);
  if (!plugin) {
    throw new Error(`Plugin "${runtimeKey}" is not loaded`);
  }
  return plugin;
}

export function resolvePluginUrl(
  runtimeKey: string,
  path: string,
  isNovel?: boolean,
): string {
  const plugin = getPlugin(runtimeKey);
  if (isUrlAbsolute(path)) {
    return path;
  }
  if (plugin.resolveUrl) {
    return plugin.resolveUrl(path, isNovel);
  }

  return `${plugin.site.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
}

export async function evaluatePlugin(
  runtimeKey: string,
  expression: string,
): Promise<unknown> {
  const plugin = getPlugin(runtimeKey);
  const result = Function(
    'plugin',
    `return (${expression});`,
  )(plugin) as unknown;
  return Promise.resolve(result);
}

export function removePlugin(runtimeKey: string): void {
  const plugin = plugins.get(runtimeKey);
  plugins.delete(runtimeKey);
  if (plugin) {
    removePluginStorageContext(runtimeKey);
  }
}
