import { CHAPTER_ID_ATTR, CHAPTER_TAG_NAME } from '../chapters/metadata.ts';
import type { ReaderConfig } from '../protocol/reader-config.ts';

export type TtsNormalizationRule = NonNullable<ReaderConfig['ttsNormalizationRules']>[number];
export type TtsTextNormalizer = (text: string) => string;

const INLINE_NODE_NAMES = new Set(['#text', 'B', 'I', 'SPAN', 'EM', 'BR', 'STRONG', 'A']);
const SKIPPED_NODE_NAMES = new Set(['STYLE', 'SCRIPT', 'NOSCRIPT', 'TEMPLATE', 'IFRAME']);
const INTERNAL_ELEMENT_IDS = new Set(['nekori-title-novel']);
const normalizers = new WeakMap<ReadonlyArray<TtsNormalizationRule>, TtsTextNormalizer>();
const trimText: TtsTextNormalizer = (text) => text.trim();

export function createTtsTextNormalizer(rules?: ReadonlyArray<TtsNormalizationRule>): TtsTextNormalizer {
  if (!rules) return trimText;
  const cached = normalizers.get(rules);
  if (cached) return cached;
  const replacements = rules.map(({ pattern, replacement }) => [new RegExp(pattern, 'gu'), replacement] as const);
  const normalize: TtsTextNormalizer = (text) => replacements.reduce(
    (value, [pattern, replacement]) => value.replace(pattern, replacement), text,
  );
  normalizers.set(rules, normalize);
  return normalize;
}

export function readableParagraphs(
  root: ParentNode,
  chapterId: string | null,
  rules?: ReadonlyArray<TtsNormalizationRule>,
): HTMLElement[] {
  const chapter = findChapter(root, chapterId);
  return chapter ? readableBlocks(chapter, createTtsTextNormalizer(rules)) : [];
}

export function extractParagraphText(
  root: ParentNode,
  chapterId: string | null,
  rules?: ReadonlyArray<TtsNormalizationRule>,
): string {
  const normalize = createTtsTextNormalizer(rules);
  return readableParagraphs(root, chapterId, rules).map((element) => normalize(element.innerText)).join('\n');
}

export function firstVisibleParagraph(
  root: HTMLElement,
  chapterId: string | null,
  rules?: ReadonlyArray<TtsNormalizationRule>,
): number {
  const window = root.ownerDocument.defaultView;
  if (!window) return 0;
  const visible = readableParagraphs(root, chapterId, rules).findIndex((element) => {
    const rect = element.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < window.innerHeight;
  });
  return visible < 0 ? 0 : visible;
}

export function taggedParagraph(
  root: HTMLElement,
  chapterId: string | null,
  rules?: ReadonlyArray<TtsNormalizationRule>,
): number {
  const markers = Array.from(root.querySelectorAll<HTMLElement>('[data-nekori-tts-target]'));
  const marker = markers[0] ?? null;
  markers.forEach((element) => element.removeAttribute('data-nekori-tts-target'));
  if (!marker) return -1;
  const paragraphs = readableParagraphs(root, chapterId, rules);
  for (let element: HTMLElement | null = marker; element; element = element.parentElement) {
    const index = paragraphs.indexOf(element);
    if (index >= 0) return index;
  }
  return -1;
}

export function readableBlocks(root: ParentNode, normalize: TtsTextNormalizer = trimText): HTMLElement[] {
  const blocks: HTMLElement[] = [];
  const visit = (element: Element): void => {
    if (isReadableBlock(element) && normalize((element as HTMLElement).innerText).length > 0 &&
        !element.closest('#nekori-image-modal,#nekori-tts-controller,#nekori-next-chapter')) {
      blocks.push(element as HTMLElement);
    }
    Array.from(element.children).forEach(visit);
  };
  if (root.nodeType === 1) visit(root as Element);
  else Array.from(root.children).forEach(visit);
  return blocks;
}

export function readableBlockAtPoint(
  root: HTMLElement,
  x: number,
  y: number,
  rules?: ReadonlyArray<TtsNormalizationRule>,
): HTMLElement | null {
  const normalize = createTtsTextNormalizer(rules);
  const candidates = new Set(readableBlocks(root, normalize));
  for (const hit of root.ownerDocument.elementsFromPoint(x, y).reverse()) {
    for (let element: HTMLElement | null = hit as HTMLElement; element; element = element.parentElement) {
      if (candidates.has(element)) return element;
      if (element === root) break;
    }
  }
  return null;
}

function findChapter(root: ParentNode, chapterId: string | null): HTMLElement | null {
  if (chapterId === null) return root.querySelector<HTMLElement>(CHAPTER_TAG_NAME);
  return Array.from(root.querySelectorAll<HTMLElement>(CHAPTER_TAG_NAME))
    .find((element) => element.getAttribute(CHAPTER_ID_ATTR) === chapterId) ?? null;
}

function isReadableBlock(element: Element): boolean {
  if (INTERNAL_ELEMENT_IDS.has(element.id) || SKIPPED_NODE_NAMES.has(element.nodeName) ||
      INLINE_NODE_NAMES.has(element.nodeName) || !element.hasChildNodes()) return false;
  return Array.from(element.childNodes).every((child) => INLINE_NODE_NAMES.has(child.nodeName));
}
