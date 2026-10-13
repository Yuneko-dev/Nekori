import { createLayout, createScrollTracking, observeShortChapter, type ScrollTrackingController } from '../layout/index.ts';
import { createPlayer } from '../media/index.ts';
import { createWidgets } from '../widgets/index.ts';
import { executeSourceScript } from '../widgets/snippets.ts';
import { CHAPTER_ID_ATTR, CHAPTER_TAG_NAME } from '../chapters/metadata.ts';
import { appendChapter, initializeInitialContent, removeChapter } from './chapter-dom.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';
import type { ReaderApi, ReaderHost, ReaderLayout, ReaderPlayer } from '../protocol/reader-api.ts';

export interface CreateReaderOptions {
  root: HTMLElement;
  host: ReaderHost;
  config: ReaderConfig;
}

export function createReader({ root, host, config }: CreateReaderOptions): ReaderApi {
  let layout: ReaderLayout | null = null;
  let player: ReaderPlayer | null = null;
  let disposeTracking: (() => void) | null = null;
  let trackingController: ScrollTrackingController | null = null;
  let disposeWidgets: (() => void) | null = null;
  let refreshWidgets: (() => void) | null = null;
  let disposeShortChapterObserver: (() => void) | null = null;
  let disposed = false;
  const view = root.ownerDocument.defaultView;
  if (!view) throw new Error('Reader root is not attached to a browser document');
  const global = view as Window & { nekori?: ReaderApi };

  const api: ReaderApi = {
    get config() { return host.config; },
    get context() { return host.context; },
    events: host.events,
    ready: Promise.resolve(),
    get layout() { return layout; },
    get player() { return player; },
    fetch(input, init) { return host.fetch(input, init); },
    refetch() { host.emit('reader.refetch', {}); },
    error(message) { host.emit('reader.error', { message }); },
    dispose() {
      if (disposed) return;
      disposed = true;
      player?.dispose();
      disposeTracking?.();
      disposeWidgets?.();
      disposeShortChapterObserver?.();
      layout?.dispose();
      if (global.nekori === api) delete global.nekori;
      host.dispose();
    },
  };
  global.nekori = api;

  const ready = host.start([
    async () => {
      const readerRoot = await waitForReaderRoot(root, host);
      if (disposed) return;
      if (config.mode !== 'download') initializeInitialContent(readerRoot, host.config);
      if (config.mode !== 'download') {
        layout = createLayout({ root: readerRoot, host });
        layout.configure(config.layout ?? {
          enabled: false,
          spread: 'single',
          direction: 'ltr',
          vertical: false,
          infinite: config.runtime.infinite,
          threshold: 0.8,
          chapterId: config.chapter.id,
        });
        registerLayoutCommands(host, layout, readerRoot);
        registerScrollTracking(host, readerRoot, config, () => layout?.enabled === true, (controller, dispose) => {
          trackingController = controller;
          disposeTracking = dispose;
        });
        const markShort = config.markShortChapterAsRead === true && config.mode === 'reader' &&
          config.layout?.enabled !== true && !config.runtime.infinite && !config.player;
        if (markShort) disposeShortChapterObserver = observeShortChapter({ host, enabled: true });
        host.onCommand('chapter.append', (payload) => appendChapter(host, readerRoot, payload, layout!, trackingController, refreshWidgets));
        host.onCommand('chapter.remove', (payload) => removeChapter(readerRoot, payload, layout!, trackingController, refreshWidgets));
      }
      if (config.player) {
        player = createPlayer(host, config.player);
      }
      if (config.mode !== 'download') {
        const widgets = createWidgets({ root: readerRoot, host, config, layout });
        refreshWidgets = () => widgets.refresh();
        disposeWidgets = () => widgets.dispose();
      }
    },
  ]);
  void ready.then(() => {
    if (disposed) return;
    if (config.pluginJavaScript) executeSourceScript(config.pluginJavaScript, root.ownerDocument);
    if (config.player?.video.direct === true && player) {
      void startDirectPlayer(player, config.player).catch((error: unknown) => {
        if (disposed) return;
        const message = error instanceof Error ? error.message : String(error);
        if (config.player?.mode === 'download') {
          host.emit('media.download.error', { code: 'direct_playback_failed', message });
        } else {
          api.error(message);
        }
      });
    }
  }).catch((error: unknown) => {
    if (disposed) return;
    const message = error instanceof Error ? error.message : String(error);
    if (config.player?.mode === 'download') host.emit('media.download.error', { code: 'runtime_initialization_failed', message });
    else api.error(message);
  });
  Object.defineProperty(api, 'ready', { enumerable: true, value: ready });
  return api;
}

function waitForReaderRoot(root: HTMLElement, host: ReaderHost): Promise<HTMLElement> {
  const document = root.ownerDocument;
  const findRoot = (): HTMLElement | null => document.querySelector<HTMLElement>('[data-nekori-reader-root], #nekori-reader');
  const existing = findRoot();
  if (existing) return Promise.resolve(existing);
  if (document.readyState !== 'loading') return Promise.resolve(document.body ?? root);
  return new Promise((resolve) => {
    const onReady = (): void => {
      unregisterDispose();
      resolve(findRoot() ?? document.body ?? root);
    };
    const unregisterDispose = host.onDispose(() => {
      document.removeEventListener('DOMContentLoaded', onReady);
      resolve(root);
    });
    document.addEventListener('DOMContentLoaded', onReady, { once: true });
  });
}

function registerLayoutCommands(host: ReaderHost, layout: ReaderLayout, root: HTMLElement): void {
  host.onCommand('layout.move', (payload) => {
    const delta = numberField(payload, 'delta');
    if (delta !== -1 && delta !== 1) throw new TypeError('layout.move delta must be -1 or 1');
    return layout.moveBy(delta);
  });
  host.onCommand('layout.seekUnit', (payload) => layout.seekUnit(numberField(payload, 'unitIndex')));
  host.onCommand('layout.seekPercent', (payload) => layout.seekPercent(numberField(payload, 'percent')));
  host.onCommand('layout.reflow', () => layout.reflow());
  host.onCommand('layout.prepareTurn', (payload) => {
    const delta = numberField(payload, 'delta');
    if (delta !== -1 && delta !== 1) throw new TypeError('layout.prepareTurn delta must be -1 or 1');
    if (!layout.enabled) return false;
    layout.prepareSilentTurn(false);
    const moved = layout.moveBy(delta, 'instant');
    if (!moved) layout.finishSilentTurn(false, false);
    return moved;
  });
  host.onCommand('layout.finishTurn', (payload) => {
    const commit = booleanField(payload, 'commit');
    return layout.finishSilentTurn(false, commit);
  });
  host.onCommand('chapter.reveal', (payload) => {
    const chapterId = stringField(payload, 'chapterId');
    const chapter = Array.from(root.querySelectorAll(CHAPTER_TAG_NAME))
      .find((element) => element.getAttribute(CHAPTER_ID_ATTR) === chapterId);
    return chapter ? layout.revealElement(chapter) : false;
  });
  host.onCommand('layout.configure', (payload) => {
    if (!isRecord(payload) || typeof payload.enabled !== 'boolean' ||
        (payload.spread !== 'single' && payload.spread !== 'double') ||
        (payload.direction !== 'ltr' && payload.direction !== 'rtl') || typeof payload.vertical !== 'boolean' ||
        typeof payload.infinite !== 'boolean' || typeof payload.threshold !== 'number' ||
        typeof payload.chapterId !== 'string') throw new TypeError('Invalid layout.configure payload');
    layout.configure(payload as unknown as NonNullable<ReaderConfig['layout']>);
  });
}

function registerScrollTracking(
  host: ReaderHost,
  root: HTMLElement,
  config: ReaderConfig,
  isPaged: () => boolean,
  attach: (controller: ScrollTrackingController, dispose: () => void) => void,
): void {
  if (!config.scrollTracking) return;
  const tracking = createScrollTracking({ root, host, isPaged });
  tracking.configure(config.scrollTracking);
  attach(tracking, () => tracking.dispose());
  host.onCommand('chapter.resetTracking', () => tracking.resetChapterTracking());
  host.onCommand('chapter.setLoadingNext', (payload) => {
    if (!isRecord(payload) || typeof payload.loading !== 'boolean') throw new TypeError('Expected loading boolean');
    tracking.setLoadingNext(payload.loading);
  });
  host.onCommand('chapter.setNoMore', (payload) => {
    if (!isRecord(payload) || typeof payload.value !== 'boolean') throw new TypeError('Expected value boolean');
    tracking.setNoMoreChapters(payload.value);
  });
  host.onCommand('scrollTracking.configure', (payload) => {
    if (!isRecord(payload) || typeof payload.infiniteScrollEnabled !== 'boolean' ||
        typeof payload.loadThreshold !== 'number' || typeof payload.doneThreshold !== 'number') {
      throw new TypeError('Expected scroll tracking configuration');
    }
    tracking.configure(payload as unknown as NonNullable<ReaderConfig['scrollTracking']>);
  });
}

async function startDirectPlayer(player: ReaderPlayer, config: NonNullable<ReaderConfig['player']>): Promise<void> {
  const { video } = config;
  const url = video.url;
  if (!url) throw new TypeError('Direct video config is missing its URL');
  if (video.type === 'm3u8') return player.playHls(url, video.hlsConfig);
  if (video.type === 'mpd') return player.playDash(url, video.dashConfig);
  if (video.type === 'iframe') return player.playIframe(url);
  if (video.type === 'video-file') return player.playDirect(url);
  throw new TypeError('Direct video config has an unsupported type');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function numberField(payload: unknown, name: string): number {
  if (!isRecord(payload) || typeof payload[name] !== 'number' || !Number.isFinite(payload[name])) {
    throw new TypeError(`${name} must be a finite number`);
  }
  return payload[name];
}

function booleanField(payload: unknown, name: string): boolean {
  if (!isRecord(payload) || typeof payload[name] !== 'boolean') throw new TypeError(`${name} must be a boolean`);
  return payload[name];
}

function stringField(payload: unknown, name: string): string {
  if (!isRecord(payload) || typeof payload[name] !== 'string') throw new TypeError(`${name} must be a string`);
  return payload[name];
}
