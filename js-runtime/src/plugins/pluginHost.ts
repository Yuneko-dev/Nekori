import { load } from 'cheerio';

import {
  ContentType,
  ContentWarning,
  defaultCover,
  fetchApi,
  fetchText,
  FilterTypes,
  NovelStatus,
} from './libs';

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

const packages: Record<string, unknown> = {
  'cheerio': { load },
  '@libs/novelStatus': { NovelStatus },
  '@libs/filterInputs': { FilterTypes },
  '@libs/defaultCover': { defaultCover },
  '@libs/fetch': { fetchApi, fetchText },
  '@libs/pluginMetadata': { ContentType, ContentWarning },
};

const plugins = new Map<string, Plugin>();

function makeRequire(): (name: string) => unknown {
  return (name: string) => {
    const module = packages[name];
    if (module === undefined) {
      // Never return {}. A missing module has to fail here, loudly, rather than turning into wrong
      // data three call frames later — the one rule the whole plugin layer is built on.
      throw new Error(`Plugin required "${name}", which is not implemented`);
    }
    return module;
  };
}

export function initPlugin(pluginId: string, rawCode: string): Plugin {
  const plugin = Function(
    'require',
    'module',
    `const exports = module.exports = {};\n${rawCode};\nreturn exports.default;`,
  )(makeRequire(), {}) as Plugin | undefined;

  if (!plugin) {
    throw new Error(`Plugin "${pluginId}" evaluated but exported no default`);
  }
  if (plugin.id !== pluginId) {
    throw new Error(`Plugin id mismatch: expected "${pluginId}", got "${plugin.id}"`);
  }

  plugins.set(pluginId, plugin);
  return plugin;
}

export function getPlugin(pluginId: string): Plugin {
  const plugin = plugins.get(pluginId);
  if (!plugin) {
    throw new Error(`Plugin "${pluginId}" is not loaded`);
  }
  return plugin;
}
