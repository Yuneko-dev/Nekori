import { createWidgets, installGestureOwnership } from '../src/widgets/index.ts';
import type { ReaderConfig } from '../src/protocol/reader-config.ts';
import type { ReaderCommandName, ReaderEventName, ReaderHost, ReaderLayout, Unsubscribe } from '../src/protocol/reader-api.ts';

interface Harness {
  commands: Map<string, (payload: unknown) => unknown>;
  events: Array<[string, Record<string, unknown>]>;
  dispose(): void;
}

declare global {
  interface Window {
    makeReaderWidgets(root: HTMLElement, layout?: ReaderLayout | null): Harness;
  }
}

window.makeReaderWidgets = (root, layout = null) => {
  const commands = new Map<string, (payload: unknown) => unknown>();
  const events: Array<[string, Record<string, unknown>]> = [];
  const config: ReaderConfig = {
    documentId: 'test', mode: 'reader', novel: { id: '1', name: 'Novel', path: '/novel' },
    chapter: { id: '1', name: 'Chapter', path: '/chapter', progress: 0 }, nextChapter: null,
    strings: { imageClose: 'Close image', tts: 'Read aloud' }, autoSaveInterval: 1000,
    runtime: {
      editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
      menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
      loadingNext: false, noMoreChapters: false,
    }, chapters: [{ id: '1', name: 'Chapter', path: '/chapter', progress: 0 }],
    ui: { bionicEnabled: false, ttsEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
  };
  const host = {
    root,
    config,
    context: { documentId: 'test', mode: 'reader', novel: config.novel, chapter: config.chapter },
    chapter: config.chapter,
    nextChapter: config.nextChapter,
    events: { subscribe: (_name: string, _listener: (payload: unknown) => void): Unsubscribe => () => {} },
    emit: (name: ReaderEventName | string, payload: Record<string, unknown> = {}) => events.push([name, payload]),
    request: async () => undefined,
    onCommand: (name: ReaderCommandName, handler: (payload: unknown, signal: AbortSignal) => unknown): Unsubscribe => {
      commands.set(name, (payload) => handler(payload, new AbortController().signal));
      return () => commands.delete(name);
    },
    fetch: async () => new Response(), fetchNative: async () => new Response(),
    start: async () => {}, dispose: () => {},
  } satisfies ReaderHost;
  const widgets = createWidgets({ root, host, config, layout });
  const removeGestures = installGestureOwnership(host);
  return { commands, events, dispose: () => { removeGestures(); widgets.dispose(); } };
};
