import type { PlayerConfig } from '../media/types.ts';
import type { LayoutConfig, ScrollTrackingConfig } from '../layout/types.ts';

export interface ReaderContext {
  documentId: string;
  mode: 'reader' | 'download' | 'error';
  novel: ReaderNovel;
  chapter: ReaderChapter;
  nextChapter: ReaderChapter | null;
  chapters: readonly ReaderChapter[];
  runtime: ReaderRuntimeState;
  strings: Readonly<Record<string, string>>;
  ui: ReaderConfig['ui'];
  style: ReaderConfig['style'];
  layout: ReaderConfig['layout'];
  player: ReaderConfig['player'];
  sourceBaseUrl: string | undefined;
  novelId: string | undefined;
  chapterId: string | undefined;
  sourceId: string | undefined;
}

export interface ReaderNovel {
  id: string;
  name: string;
  path: string;
}

export interface ReaderChapter {
  id: string;
  name: string;
  path: string;
  progress: number;
  url?: string;
  number?: number;
}

export interface ReaderInitialContent {
  kind: 'plainText';
  text: string;
}

export interface ReaderRuntimeState {
  editing: boolean;
  infinite: boolean;
  textSelectionBlocked: boolean;
  forcedLowercase: boolean;
  menuVisible: boolean;
  immersive: boolean;
  ttsState: 'playing' | 'paused' | 'stopped' | 'starting';
  loadingChapter: boolean;
  loadingNext: boolean;
  noMoreChapters: boolean;
}

export interface ReaderConfig {
  documentId: string;
  mode: 'reader' | 'download' | 'error';
  novel: ReaderNovel;
  chapter: ReaderChapter;
  nextChapter: ReaderChapter | null;
  strings: Record<string, string>;
  autoSaveInterval: number;
  proxyEndpoint?: string;
  runtime: ReaderRuntimeState;
  chapters: ReaderChapter[];
  initialContent?: ReaderInitialContent;
  layout?: LayoutConfig;
  ui?: {
    bionicEnabled: boolean;
    ttsEnabled: boolean;
    labels: { tts: string; imageClose: string };
  };
  style?: Record<string, unknown>;
  scrollTracking?: ScrollTrackingConfig;
  player?: PlayerConfig;
  markShortChapterAsRead?: boolean;
  /** Native text rules keep omitted paragraphs and highlight indices aligned with its speech queue. */
  ttsNormalizationRules?: ReadonlyArray<{ pattern: string; replacement: string }>;
  pluginJavaScript?: string;
  context?: Partial<ReaderContext>;
}

export class ReaderConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReaderConfigError';
  }
}

export function parseReaderConfig(value: unknown): ReaderConfig {
  if (!isRecord(value)) throw new ReaderConfigError('Reader config must be a JSON object');
  if (typeof value.documentId !== 'string' || value.documentId.length === 0) {
    throw new ReaderConfigError('Reader config is missing documentId');
  }
  if (value.mode !== 'reader' && value.mode !== 'download' && value.mode !== 'error') {
    throw new ReaderConfigError('Reader config has an unsupported mode');
  }
  if (!isNovel(value.novel) || !isChapter(value.chapter) || !isRuntimeState(value.runtime)) {
    throw new ReaderConfigError('Reader config is missing novel, chapter, or runtime state');
  }
  if (!Array.isArray(value.chapters) || !value.chapters.every(isChapter) || !isStringMap(value.strings) ||
      !isFiniteNumber(value.autoSaveInterval) || !(value.nextChapter === null || isChapter(value.nextChapter))) {
    throw new ReaderConfigError('Reader config has invalid chapters, strings, or auto-save interval');
  }
  if (value.layout !== undefined && !isLayoutConfig(value.layout)) throw new ReaderConfigError('Reader layout config is invalid');
  if (value.scrollTracking !== undefined && !isScrollTrackingConfig(value.scrollTracking)) {
    throw new ReaderConfigError('Reader scroll tracking config is invalid');
  }
  if (value.player !== undefined && !isPlayerConfig(value.player)) throw new ReaderConfigError('Reader player config is invalid');
  if (value.context !== undefined && !isRecord(value.context)) throw new ReaderConfigError('Reader context must be an object');
  if ((value.proxyEndpoint !== undefined && !isString(value.proxyEndpoint)) ||
      (value.pluginJavaScript !== undefined && !isString(value.pluginJavaScript)) ||
      (value.markShortChapterAsRead !== undefined && typeof value.markShortChapterAsRead !== 'boolean')) {
    throw new ReaderConfigError('Reader config has invalid optional fields');
  }
  if (value.style !== undefined && !isRecord(value.style)) throw new ReaderConfigError('Reader style config must be an object');
  if (value.ui !== undefined && !isUiConfig(value.ui)) throw new ReaderConfigError('Reader UI config is invalid');
  if (value.initialContent !== undefined && (!isRecord(value.initialContent) || value.initialContent.kind !== 'plainText' ||
      typeof value.initialContent.text !== 'string')) {
    throw new ReaderConfigError('Reader initial content must be plain text');
  }
  if (value.ttsNormalizationRules !== undefined && (!Array.isArray(value.ttsNormalizationRules) ||
      !value.ttsNormalizationRules.every((rule) => isRecord(rule) && isString(rule.pattern) && isString(rule.replacement)))) {
    throw new ReaderConfigError('Reader TTS normalization rules are invalid');
  }
  return value as unknown as ReaderConfig;
}

export function readReaderConfig(root: ParentNode): ReaderConfig {
  const element = root.querySelector('#nekori-reader-config');
  if (!element || element.tagName !== 'SCRIPT' || element.getAttribute('type') !== 'application/json' || !element.textContent) {
    throw new ReaderConfigError('Missing #nekori-reader-config JSON element');
  }
  let value: unknown;
  try {
    value = JSON.parse(element.textContent) as unknown;
  } catch {
    throw new ReaderConfigError('Reader config contains invalid JSON');
  }
  return parseReaderConfig(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNovel(value: unknown): value is ReaderNovel {
  return isRecord(value) && isString(value.id) && isString(value.name) && isString(value.path);
}

function isChapter(value: unknown): value is ReaderChapter {
  return isRecord(value) && isString(value.id) && isString(value.name) && isString(value.path) &&
    isFiniteNumber(value.progress) && (value.number === undefined || isFiniteNumber(value.number)) &&
    (value.url === undefined || isString(value.url));
}

function isRuntimeState(value: unknown): value is ReaderRuntimeState {
  return isRecord(value) && typeof value.editing === 'boolean' && typeof value.infinite === 'boolean' &&
    typeof value.textSelectionBlocked === 'boolean' && typeof value.forcedLowercase === 'boolean' &&
    typeof value.menuVisible === 'boolean' && typeof value.immersive === 'boolean' &&
    (value.ttsState === 'playing' || value.ttsState === 'paused' || value.ttsState === 'stopped' || value.ttsState === 'starting') &&
    typeof value.loadingChapter === 'boolean' && typeof value.loadingNext === 'boolean' &&
    typeof value.noMoreChapters === 'boolean';
}

function isStringMap(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every(isString);
}

function isLayoutConfig(value: unknown): value is LayoutConfig {
  return isRecord(value) && typeof value.enabled === 'boolean' &&
    (value.spread === 'single' || value.spread === 'double') &&
    (value.direction === 'ltr' || value.direction === 'rtl') && typeof value.vertical === 'boolean' &&
    typeof value.infinite === 'boolean' && isFiniteNumber(value.threshold) && isString(value.chapterId);
}

function isScrollTrackingConfig(value: unknown): value is ScrollTrackingConfig {
  return isRecord(value) && typeof value.infiniteScrollEnabled === 'boolean' &&
    isFiniteNumber(value.loadThreshold) && isFiniteNumber(value.doneThreshold);
}

function isUiConfig(value: unknown): boolean {
  return isRecord(value) && typeof value.bionicEnabled === 'boolean' && typeof value.ttsEnabled === 'boolean' &&
    isRecord(value.labels) && isString(value.labels.tts) && isString(value.labels.imageClose);
}

function isPlayerConfig(value: unknown): value is PlayerConfig {
  if (!isRecord(value) || (value.mode !== 'playback' && value.mode !== 'download') || !isRecord(value.video)) return false;
  const video = value.video;
  if (!isString(video.path) || !isFiniteNumber(video.progress)) return false;
  const supportedType = video.type === undefined || video.type === 'm3u8' || video.type === 'mpd' ||
    video.type === 'video-file' || video.type === 'iframe';
  const validUrl = video.url === undefined || isString(video.url);
  return supportedType && validUrl && (video.direct !== true ||
    (video.type !== undefined && typeof video.url === 'string' && video.url.length > 0));
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}
