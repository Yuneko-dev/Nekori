import { parseReaderConfig, type ReaderConfig, type ReaderContext, type ReaderRuntimeState } from '../protocol/reader-config.ts';
import type {
  ReaderCommandName,
  ReaderCommandHandler,
  ReaderEventBus,
  ReaderHost,
  ReaderRequestName,
  Unsubscribe,
} from '../protocol/reader-api.ts';
import type { NativeReaderTransport } from './session.ts';
import { createRuntimeSession } from './session.ts';

export interface CreateRuntimeHostOptions {
  root: HTMLElement;
  config: ReaderConfig;
  transport: NativeReaderTransport;
}

export function createRuntimeHost({ root, config, transport }: CreateRuntimeHostOptions): ReaderHost {
  const session = createRuntimeSession(config, transport);
  let currentConfig = config;
  const view = root.ownerDocument.defaultView;
  if (!view) throw new Error('Reader root is not attached to a browser document');
  const nativeFetch = view.fetch.bind(view);
  const browserRequest = view.Request;
  const browserHeaders = view.Headers;
  const proxyUnavailableError = 'This plugin cannot use window.nekori.fetch because the local proxy is disabled. Go to Settings > Advanced > Novel WebView, enable Allow local proxy API, then reopen the reader.';
  const localListeners = new Map<string, Set<(payload: unknown) => void>>();
  const cleanups = new Set<Unsubscribe>();
  const context: ReaderContext = {
    get documentId() { return currentConfig.documentId; },
    get mode() { return currentConfig.mode; },
    get novel() { return currentConfig.novel; },
    get chapter() { return currentConfig.chapter; },
    get nextChapter() { return currentConfig.nextChapter; },
    get chapters() { return currentConfig.chapters; },
    get runtime() { return currentConfig.runtime; },
    get strings() { return currentConfig.strings; },
    get ui() { return currentConfig.ui; },
    get style() { return currentConfig.style; },
    get layout() { return currentConfig.layout; },
    get player() { return currentConfig.player; },
    get sourceBaseUrl() { return currentConfig.context?.sourceBaseUrl; },
    get novelId() { return currentConfig.context?.novelId ?? currentConfig.novel.id; },
    get chapterId() { return currentConfig.context?.chapterId ?? currentConfig.chapter.id; },
    get sourceId() { return currentConfig.context?.sourceId; },
  };

  const events: ReaderEventBus = {
    subscribe(name: string, listener: (payload: unknown) => void): Unsubscribe {
      const nativeUnsubscribe = session.onEvent(name, listener);
      const listeners = localListeners.get(name) ?? new Set();
      listeners.add(listener);
      localListeners.set(name, listeners);
      return () => {
        nativeUnsubscribe();
        listeners.delete(listener);
        if (listeners.size === 0) localListeners.delete(name);
      };
    },
  };

  const notifyStateChanged = (event?: string): void => {
    const payload = { runtime: currentConfig.runtime, ...(event === undefined ? {} : { event }) };
    for (const listener of localListeners.get('document.stateChanged') ?? []) listener(payload);
  };

  session.registerCommand('document.configure', (payload) => {
    const nextConfig = parseReaderConfig(payload);
    if (nextConfig.documentId !== currentConfig.documentId) throw new TypeError('Cannot replace the active document identity');
    currentConfig = nextConfig;
    notifyStateChanged('document.configure');
    return context;
  });
  session.registerCommand('document.patchState', (payload) => {
    currentConfig = applyRuntimePatch(currentConfig, payload);
    notifyStateChanged(isRecord(payload) && typeof payload.event === 'string' ? payload.event : 'document.patchState');
    return currentConfig.runtime;
  });

  return {
    get root() {
      return root.ownerDocument.querySelector<HTMLElement>('[data-nekori-reader-root], #nekori-reader') ??
        root.ownerDocument.body ?? root;
    },
    get config() { return currentConfig; },
    context,
    events,
    get strings() { return currentConfig.strings; },
    get chapter() { return currentConfig.chapter; },
    get nextChapter() { return currentConfig.nextChapter; },
    emit(name: string, payload?: unknown) {
      session.emit(name, payload);
    },
    request(name: ReaderRequestName, payload?: unknown, signal?: AbortSignal) {
      return session.request(name, payload, signal);
    },
    onCommand(name: ReaderCommandName, handler: ReaderCommandHandler) {
      return session.registerCommand(name, handler);
    },
    onDispose(cleanup: Unsubscribe) {
      cleanups.add(cleanup);
      return () => cleanups.delete(cleanup);
    },
    fetch(input, init) {
      const endpoint = currentConfig.proxyEndpoint;
      if (!endpoint) {
        session.emit('reader.error', { message: proxyUnavailableError });
        return Promise.reject(new Error(proxyUnavailableError));
      }
      const inputRequest = input instanceof browserRequest ? input : null;
      const requestUrl = inputRequest?.url ?? String(input);
      const incomingHeaders = init?.headers ?? inputRequest?.headers;
      const forwardedHeaders = new browserHeaders();
      new browserHeaders(incomingHeaders).forEach((value, name) => {
        forwardedHeaders.set(`x-ln-forward-header-${name.toLowerCase()}`, value);
      });
      const proxyUrl = `${endpoint}?url=${encodeURIComponent(requestUrl)}`;
      if (inputRequest) {
        const sourceRequest = new browserRequest(inputRequest, init);
        const proxiedRequest = new browserRequest(proxyUrl, sourceRequest);
        for (const name of Array.from(proxiedRequest.headers.keys())) proxiedRequest.headers.delete(name);
        forwardedHeaders.forEach((value, name) => proxiedRequest.headers.set(name, value));
        return nativeFetch(proxiedRequest);
      }
      return nativeFetch(proxyUrl, { ...init, headers: forwardedHeaders });
    },
    fetchNative(input: RequestInfo | URL, init?: RequestInit) {
      return nativeFetch(input, init);
    },
    boot() {
      session.boot();
    },
    start(initializers?: Array<() => void | Promise<void>>) {
      return session.start(initializers);
    },
    dispose() {
      for (const cleanup of cleanups) cleanup();
      cleanups.clear();
      localListeners.clear();
      session.dispose();
    },
  };
}

function applyRuntimePatch(config: ReaderConfig, value: unknown): ReaderConfig {
  if (!isRecord(value) || !isRecord(value.patch)) throw new TypeError('Invalid document.patchState payload');
  const patch = value.patch;
  const runtime: ReaderRuntimeState = {
    editing: booleanOr(patch.editing ?? patch.isEditMode, config.runtime.editing),
    infinite: booleanOr(patch.infinite ?? patch.isInfiniteScroll, config.runtime.infinite),
    textSelectionBlocked: booleanOr(patch.textSelectionBlocked, config.runtime.textSelectionBlocked),
    forcedLowercase: booleanOr(patch.forcedLowercase, config.runtime.forcedLowercase),
    menuVisible: booleanOr(patch.menuVisible, config.runtime.menuVisible),
    immersive: booleanOr(patch.immersive, config.runtime.immersive),
    ttsState: ttsStateOr(patch.ttsState, config.runtime.ttsState),
    loadingChapter: booleanOr(patch.loadingChapter, config.runtime.loadingChapter),
    loadingNext: booleanOr(patch.loadingNext, config.runtime.loadingNext),
    noMoreChapters: booleanOr(patch.noMoreChapters, config.runtime.noMoreChapters),
  };
  return { ...config, runtime };
}

function booleanOr(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function ttsStateOr(value: unknown, fallback: ReaderRuntimeState['ttsState']): ReaderRuntimeState['ttsState'] {
  return value === 'playing' || value === 'paused' || value === 'stopped' || value === 'starting' ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
