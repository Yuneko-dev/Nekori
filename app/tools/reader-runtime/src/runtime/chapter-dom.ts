import {
  CHAPTER_ID_ATTR,
  CHAPTER_TAG_NAME,
  type ChapterMetadata,
} from '../chapters/metadata.ts';
import {
  createChapterElements,
  appendChapter as appendNodes,
  removeChapter as removeNodes,
} from '../chapters/dom.ts';
import { applyProcessedContent } from '../chapters/index.ts';
import type { ReaderHost, ReaderLayout } from '../protocol/reader-api.ts';
import type { ScrollTrackingController } from '../layout/index.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';

export function initializeInitialContent(root: HTMLElement, config: ReaderConfig): void {
  const chapter = Array.from(root.querySelectorAll<HTMLElement>(CHAPTER_TAG_NAME))
    .find((element) => element.getAttribute(CHAPTER_ID_ATTR) === config.chapter.id) ?? null;
  if (!chapter) {
    if (config.mode === 'error' && !config.initialContent) return;
    const nodes = createChapterElements(root.ownerDocument, chapterMetadata(config.chapter));
    if (config.initialContent) applyProcessedContent(root.ownerDocument, nodes.element, config.initialContent);
    appendNodes(root, nodes);
    return;
  }
  if (config.initialContent && chapter.childElementCount === 0 && (chapter.textContent ?? '').trim().length === 0) {
    applyProcessedContent(root.ownerDocument, chapter, config.initialContent);
  }
}

export function appendChapter(
  host: ReaderHost,
  root: HTMLElement,
  payload: unknown,
  layout: ReaderLayout,
  tracking: ScrollTrackingController | null,
  refreshWidgets: (() => void) | null,
): boolean {
  if (!isRecord(payload) || !isRecord(payload.chapter) || !isRecord(payload.content)) throw new TypeError('Invalid chapter.append payload');
  const metadata = payload.chapter;
  if (typeof metadata.id !== 'string' || typeof metadata.name !== 'string' || typeof metadata.path !== 'string' ||
      typeof metadata.absoluteUrl !== 'string' || typeof metadata.number !== 'number') throw new TypeError('Invalid appended chapter metadata');
  const chapterMetadata: ChapterMetadata = {
    id: metadata.id,
    name: metadata.name,
    number: metadata.number,
    path: metadata.path,
    url: metadata.absoluteUrl,
    ...(metadata.direction === 'ltr' || metadata.direction === 'rtl' ? { direction: metadata.direction } : {}),
    ...(typeof metadata.language === 'string' ? { language: metadata.language } : {}),
  };
  const nodes = createChapterElements(root.ownerDocument, chapterMetadata);
  const content = payload.content;
  if (content.kind === 'plainText' && typeof content.text === 'string') {
    applyProcessedContent(root.ownerDocument, nodes.element, { kind: 'plainText', text: content.text });
  } else if (content.kind === 'html' && typeof content.html === 'string') {
    applyProcessedContent(root.ownerDocument, nodes.element, { kind: 'html', html: content.html });
  } else {
    throw new TypeError('Invalid appended chapter content');
  }
  appendNodes(root, nodes);
  layout.reflow();
  tracking?.updateChapterBoundaries();
  refreshWidgets?.();
  host.emit('chapter.appended', { chapterId: metadata.id });
  return true;
}

export function removeChapter(
  root: HTMLElement,
  payload: unknown,
  layout: ReaderLayout,
  tracking: ScrollTrackingController | null,
  refreshWidgets: (() => void) | null,
): boolean {
  const chapterId = stringField(payload, 'chapterId');
  if (!removeNodes(root, chapterId)) return false;
  layout.reflow();
  tracking?.updateChapterBoundaries();
  refreshWidgets?.();
  return true;
}

function chapterMetadata(chapter: ReaderConfig['chapter']): ChapterMetadata {
  return {
    id: chapter.id,
    name: chapter.name,
    number: chapter.number ?? 0,
    path: chapter.path,
    url: chapter.url ?? '',
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringField(payload: unknown, name: string): string {
  if (!isRecord(payload) || typeof payload[name] !== 'string') throw new TypeError(`${name} must be a string`);
  return payload[name];
}

