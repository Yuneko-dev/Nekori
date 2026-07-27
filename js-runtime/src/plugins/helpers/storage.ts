import type { Spec } from '../../../specs/NativeHostApi';

type StoredValue = {
  created: number;
  expires?: number;
  value: unknown;
};

type StorageMutation =
  | { type: 'set'; key: string; value: string }
  | { type: 'delete'; key: string }
  | { type: 'clear' };

type Snapshot = {
  database: Record<string, string>;
  localStorage: string;
  sessionStorage: string;
};

type PluginStorageContext = {
  pluginId: string;
  values: Map<string, StoredValue>;
  mutations: StorageMutation[];
  localStorage: unknown;
  sessionStorage: unknown;
};

const contexts = new Map<string, PluginStorageContext>();

function nativeHostApi(): Spec {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require('../../../specs/NativeHostApi').default as Spec;
}

function parseSnapshot(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

function parseStoredValue(value: string): StoredValue {
  try {
    const parsed = JSON.parse(value) as Partial<StoredValue>;
    if ('value' in parsed && typeof parsed.created === 'number') {
      return parsed as StoredValue;
    }
  } catch {
    // Legacy storage entries were plain strings.
  }
  return { created: 0, value };
}

export async function hydratePluginStorage(pluginId: string, runtimeKey: string): Promise<void> {
  const snapshot = JSON.parse(await nativeHostApi().loadPluginStorage(pluginId)) as Snapshot;
  contexts.set(runtimeKey, {
    pluginId,
    values: new Map(
      Object.entries(snapshot.database).map(([key, value]) => [key, parseStoredValue(value)]),
    ),
    mutations: [],
    localStorage: parseSnapshot(snapshot.localStorage),
    sessionStorage: parseSnapshot(snapshot.sessionStorage),
  });
}

function context(runtimeKey: string): PluginStorageContext {
  const value = contexts.get(runtimeKey);
  if (!value) {
    throw new Error(`Storage for runtime "${runtimeKey}" was not hydrated`);
  }
  return value;
}

export function storageModule(runtimeKey: string): unknown {
  const state = context(runtimeKey);
  return {
    storage: {
      get(key: string, raw = false): unknown {
        const stored = state.values.get(key);
        if (!stored) return undefined;
        if (stored.expires !== undefined && stored.expires <= Date.now()) {
          state.values.delete(key);
          state.mutations.push({ type: 'delete', key });
          return undefined;
        }
        return raw
          ? {
              created: new Date(stored.created),
              ...(stored.expires === undefined ? {} : { expires: new Date(stored.expires) }),
              value: stored.value,
            }
          : stored.value;
      },
      set(key: string, value: unknown, expires?: Date | number): void {
        if (value === undefined || value === null) {
          this.delete(key);
          return;
        }
        const stored: StoredValue = {
          created: Date.now(),
          ...(expires === undefined
            ? {}
            : { expires: expires instanceof Date ? expires.getTime() : expires }),
          value,
        };
        state.values.set(key, stored);
        state.mutations.push({ type: 'set', key, value: JSON.stringify(stored) });
      },
      delete(key: string): void {
        state.values.delete(key);
        state.mutations.push({ type: 'delete', key });
      },
      getAllKeys(): string[] {
        return Array.from(state.values.keys());
      },
      clearAll(): void {
        state.values.clear();
        state.mutations.push({ type: 'clear' });
      },
    },
    localStorage: { get: () => state.localStorage },
    sessionStorage: { get: () => state.sessionStorage },
  };
}

export function getPluginStorageValue(runtimeKey: string, key: string): unknown {
  const module = storageModule(runtimeKey) as {
    storage: { get(storageKey: string): unknown };
  };
  return module.storage.get(key);
}

export async function setPluginStorageValue(
  runtimeKey: string,
  key: string,
  value: unknown,
): Promise<void> {
  const module = storageModule(runtimeKey) as {
    storage: { set(storageKey: string, storedValue: unknown): void };
  };
  module.storage.set(key, value);
  await flushPluginStorage(runtimeKey);
}

export async function flushPluginStorage(runtimeKey: string): Promise<void> {
  const state = contexts.get(runtimeKey);
  if (!state || state.mutations.length === 0) return;
  const mutations = state.mutations.splice(0);
  try {
    await nativeHostApi().applyPluginStorageMutation(state.pluginId, JSON.stringify(mutations));
  } catch (error) {
    state.mutations.unshift(...mutations);
    throw error;
  }
}

export function removePluginStorageContext(runtimeKey: string): void {
  contexts.delete(runtimeKey);
}
