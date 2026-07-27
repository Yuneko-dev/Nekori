import {
  ContentType,
  ContentWarning,
  defaultCover,
  fetchApi,
  fetchFile,
  fetchProto,
  fetchText,
  FilterTypes,
  isAbsoluteUrl,
  NovelStatus,
  unsupportedWebView,
} from './libs';
import {
  hydratePluginStorage,
  removePluginStorageContext,
  storageModule,
} from './helpers/storage';

/**
 * Loads and runs LNReader plugins.
 *
 * The evaluation step is LNReader's, verbatim in shape
 * (`lnreader/src/plugins/pluginManager.ts:152-196`): a plugin is a CommonJS module handed a `require`
 * closed over its own id, evaluated with the `Function` constructor. **Whether Hermes permits that at
 * all is the question this spike exists to answer** — Hermes ships without `eval` in some
 * configurations, and every LNReader plugin is fetched as source at runtime.
 */

type Plugin = {
  [key: string]: unknown;
  id: string;
  name: string;
  version: string;
  site: string;
  /** Declared filter definitions; each entry carries its own default `value`. */
  filters?: Record<string, { value: unknown }>;
  popularNovels: (page: number, options: unknown) => Promise<unknown[]>;
  searchNovels: (query: string, page: number) => Promise<unknown[]>;
  parseNovel: (path: string) => Promise<unknown>;
  /** Present only on paged/volume sources — Báo Mới is one. */
  parsePage: (path: string, page: string) => Promise<unknown>;
  parseChapter: (path: string) => Promise<string>;
};

const localPackages: Record<string, unknown> = {
  '@libs/novelStatus': { NovelStatus },
  '@libs/filterInputs': { FilterTypes },
  '@libs/defaultCover': { defaultCover },
  '@libs/fetch': { fetchApi, fetchFile, fetchProto, fetchText },
  '@libs/isAbsoluteUrl': { isUrlAbsolute: isAbsoluteUrl },
  '@libs/pluginMetadata': { ContentType, ContentWarning },
  '@libs/webview': unsupportedWebView,
};

const packageAliases: Record<string, string> = {
  '@libs/aes': '@noble/ciphers/aes.js',
  '@libs/buffer': 'buffer',
  '@libs/crypto': 'crypto-browserify',
  'crypto': 'crypto-browserify',
  'lodash': 'lodash-es',
  'stream': 'stream-browserify',
};

const packageFactories: Record<string, () => unknown> = {
  '@noble/ciphers/aes.js': () => require('@noble/ciphers/aes.js'),
  'buffer': () => require('buffer'),
  'cheerio': () => require('cheerio'),
  'crypto-browserify': () => require('crypto-browserify'),
  'dayjs': () => require('dayjs'),
  'html-entities': () => require('html-entities'),
  'htmlparser2': () => require('htmlparser2'),
  'lodash-es': () => require('lodash-es'),
  'node-html-markdown': () => require('node-html-markdown'),
  'protobufjs': () => require('protobufjs'),
  'stream-browserify': () => require('stream-browserify'),
  'urlencode': () => require('urlencode'),
};

const resolvedPackages = new Map<string, unknown>();

function resolvePackage(name: string): unknown {
  const local = localPackages[name];
  if (local !== undefined) return local;

  const canonicalName = packageAliases[name] ?? name;
  const cached = resolvedPackages.get(canonicalName);
  if (cached !== undefined) return cached;

  const factory = packageFactories[canonicalName];
  if (!factory) {
    // Never return {}. A missing module has to fail here, loudly, rather than turning into wrong
    // data three call frames later.
    throw new Error(`Plugin required "${name}", which is not implemented`);
  }
  const module = factory();
  resolvedPackages.set(canonicalName, module);
  return module;
}

const plugins = new Map<string, Plugin>();

function makeRequire(runtimeKey: string): (name: string) => unknown {
  return (name: string) => {
    if (name === '@libs/storage') {
      return storageModule(runtimeKey);
    }
    const module = resolvePackage(name);
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
    const plugin = Function(
      'require',
      'module',
      `const exports = module.exports = {};\n${rawCode};\nreturn exports.default;`,
    )(makeRequire(runtimeKey), {}) as Plugin | undefined;

    if (!plugin) {
      throw new Error(`Plugin "${pluginId}" evaluated but exported no default`);
    }
    if (plugin.id !== pluginId) {
      throw new Error(`Plugin id mismatch: expected "${pluginId}", got "${plugin.id}"`);
    }

    plugins.set(runtimeKey, plugin);
    return plugin;
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

export async function evaluatePlugin(
  runtimeKey: string,
  expression: string,
  siteOverride?: string,
): Promise<unknown> {
  const plugin = getPlugin(runtimeKey);
  if (siteOverride) {
    plugin.site = siteOverride;
    plugin.sourceSite = siteOverride;
  }
  const result = Function('plugin', `return (${expression});`)(plugin) as unknown;
  return Promise.resolve(result);
}

export function removePlugin(runtimeKey: string): void {
  const plugin = plugins.get(runtimeKey);
  plugins.delete(runtimeKey);
  if (plugin) {
    removePluginStorageContext(runtimeKey);
  }
}
