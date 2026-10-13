import type { ReaderConfig, ReaderContext } from './reader-config.ts';
import type { LayoutController } from '../layout/types.ts';
import type { PlayerDelegate } from '../media/types.ts';

export type Unsubscribe = () => void;
export type ReaderCommandHandler = (payload: unknown, signal: AbortSignal) => unknown | Promise<unknown>;

export interface ReaderEventMap {
  'layout.positionChanged': {
    chapterId: string;
    unitIndex: number;
    unitCount: number;
    firstPage: number;
    lastPage: number;
    totalPages: number;
    progress?: number;
  };
  'scroll.progressPersist': {
    progress: number;
  };
  'scroll.progressPreview': { progress: number };
  'chapter.visible': { chapterId: string; progress: number };
  'navigation.loadNext': Record<string, never>;
  'gesture.claim': { owner: 'blocked' | 'image' | 'surface' };
  'gesture.ready': Record<string, never>;
  'reader.error': { message: string };
  'media.requestProtectedPlayback': Record<string, never>;
  'reader.saveVideoProgress': { chapterId: string; path: string; progress: number };
  'reader.refetch': Record<string, never>;
  'navigation.next': Record<string, never>;
  'edit.changed': Record<string, never>;
  'edit.save': { content: string };
  'summary.action': { chapterId: string; action: 'cancel' | 'close' | 'regenerate' };
  'scroll.restoreComplete': { token: number };
  'chapter.appended': { chapterId: string };
  'tts.toggle': Record<string, never>;
  'tts.startAtParagraph': { index: number };
  'tts.startAtTaggedParagraph': Record<string, never>;
  'chapter.markShort': Record<string, never>;
  'chapter.retry': Record<string, never>;
  'feedback.retry': Record<string, never>;
  'clipboard.copy': { text: string };
  'media.playLocalVideo': Record<string, never>;
  'navigation.openNext': Record<string, never>;
  'navigation.previous': Record<string, never>;
  'tts.start': Record<string, never>;
  'tts.pause': Record<string, never>;
  'tts.resume': Record<string, never>;
  'tts.stop': Record<string, never>;
  'scroll.seekPercent': { percent: number };
  'media.download.ready': { container: string };
  'media.download.progress': { current: number; total: number };
  'media.download.activity': Record<string, never>;
  'media.download.complete': Record<string, never>;
  'media.download.error': { code: string; message: string };
  'document.stateChanged': { runtime: ReaderConfig['runtime']; event?: string };
  'reader.log': { message: string };
}

export type ReaderEventName = keyof ReaderEventMap;
export type ReaderRequestName = 'media.requestProtectedPlayback';

export type ReaderCommandName =
  | 'document.configure' | 'document.patchState' | 'chapter.append' | 'chapter.remove' | 'chapter.reveal' | 'chapter.resetTracking'
  | 'edit.enter' | 'edit.exit' | 'edit.discard'
  | 'chapter.setLoadingNext' | 'chapter.setNoMore' | 'scroll.restoreVertical' | 'scroll.byViewport'
  | 'scroll.seekVertical' | 'scroll.autoStart' | 'scroll.autoStop' | 'scroll.autoStatus' | 'layout.configure'
  | 'layout.move' | 'layout.seekUnit' | 'layout.seekPercent' | 'layout.reflow' | 'layout.prepareTurn'
  | 'layout.finishTurn' | 'tts.highlight' | 'tts.clearHighlight' | 'tts.extractText'
  | 'tts.firstVisibleParagraph' | 'tts.taggedParagraph' | 'selection.read' | 'selection.readAnchor'
  | 'selection.clear' | 'selection.blur' | 'style.apply' | 'ui.configure' | 'ui.setTtsEnabled'
  | 'ui.setBionic' | 'ui.refresh' | 'ui.configureNextChapter' | 'scrollTracking.configure' | 'anchors.install'
  | 'summary.focus' | 'summary.render' | 'summary.remove' | 'feedback.show' | 'feedback.clear' | 'snippet.run';

export interface ReaderEventBus {
  subscribe<K extends keyof ReaderEventMap>(name: K, listener: (payload: ReaderEventMap[K]) => void): Unsubscribe;
  subscribe(name: string, listener: (payload: unknown) => void): Unsubscribe;
}

export interface ReaderHost {
  readonly root: HTMLElement;
  readonly config: ReaderConfig;
  readonly context: ReaderContext;
  readonly events: ReaderEventBus;
  readonly strings: Readonly<Record<string, string>>;
  readonly chapter: Readonly<ReaderConfig['chapter']>;
  readonly nextChapter: Readonly<ReaderConfig['chapter']> | null;
  emit<K extends ReaderEventName>(name: K, payload: ReaderEventMap[K]): void;
  emit(name: string, payload?: unknown): void;
  request(name: ReaderRequestName, payload?: unknown, signal?: AbortSignal): Promise<unknown>;
  onCommand(name: ReaderCommandName, handler: ReaderCommandHandler): Unsubscribe;
  onDispose(cleanup: Unsubscribe): Unsubscribe;
  fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  fetchNative(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  boot(): void;
  start(initializers?: Array<() => void | Promise<void>>): Promise<void>;
  dispose(): void;
}

export type ReaderLayout = LayoutController;
export type ReaderPlayer = PlayerDelegate;

export interface ReaderApi {
  readonly config: ReaderConfig;
  readonly context: ReaderContext;
  readonly events: ReaderEventBus;
  readonly ready: Promise<void>;
  readonly layout: ReaderLayout | null;
  readonly player: ReaderPlayer | null;
  fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  error(message: string): void;
  dispose(): void;
}
