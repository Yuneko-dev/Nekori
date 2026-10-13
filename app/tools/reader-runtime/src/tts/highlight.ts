import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';
import type { ReaderLayout } from '../protocol/reader-api.ts';
import { extractParagraphText, firstVisibleParagraph, readableParagraphs, taggedParagraph } from './paragraphs.ts';

const HIGHLIGHT_CLASS = 'nekori-tts-highlight';

export function registerTtsDomCommands(host: ReaderHost, layout: ReaderLayout | null): Unsubscribe[] {
  return [
    host.onCommand('tts.extractText', (payload) => {
      const chapterId = optionalChapterId(payload);
      return extractParagraphText(host.root, chapterId, host.config.ttsNormalizationRules);
    }),
    host.onCommand('tts.firstVisibleParagraph', (payload) => {
      const chapterId = optionalChapterId(payload);
      return firstVisibleParagraph(host.root, chapterId, host.config.ttsNormalizationRules);
    }),
    host.onCommand('tts.taggedParagraph', (payload) => {
      const chapterId = optionalChapterId(payload);
      return taggedParagraph(host.root, chapterId, host.config.ttsNormalizationRules);
    }),
    host.onCommand('tts.highlight', (payload) => applyHighlight(host, layout, payload)),
    host.onCommand('tts.clearHighlight', () => clearHighlight(host.root)),
  ];
}

export function clearHighlight(root: HTMLElement): void {
  root.querySelectorAll(`.${HIGHLIGHT_CLASS}`).forEach((element) => {
    element.classList.remove(HIGHLIGHT_CLASS);
    (element as HTMLElement).style.removeProperty('--nekori-tts-highlight-background');
    (element as HTMLElement).style.removeProperty('--nekori-tts-highlight-color');
    (element as HTMLElement).removeAttribute('data-nekori-tts-highlight-style');
  });
}

function applyHighlight(host: ReaderHost, layout: ReaderLayout | null, value: unknown): void {
  if (!isRecord(value) || typeof value.paragraphIndex !== 'number') throw new TypeError('Invalid TTS highlight payload');
  const chapterId = typeof value.chapterId === 'string' ? value.chapterId : null;
  const paragraph = readableParagraphs(host.root, chapterId, host.config.ttsNormalizationRules)[
    Math.max(0, Math.trunc(value.paragraphIndex))
  ];
  clearHighlight(host.root);
  if (!paragraph) return;
  const background = safeColor(value.backgroundColor);
  const color = safeColor(value.textColor);
  if (background) paragraph.style.setProperty('--nekori-tts-highlight-background', background);
  if (color) paragraph.style.setProperty('--nekori-tts-highlight-color', color);
  paragraph.dataset.nekoriTtsHighlightStyle = value.style === 'underline' || value.style === 'outline'
    ? value.style : 'background';
  paragraph.classList.add(HIGHLIGHT_CLASS);
  if (value.keepInView === true) {
    if (layout?.enabled) layout.revealElement(paragraph);
    else paragraph.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

function optionalChapterId(value: unknown): string | null {
  if (!isRecord(value) || value.chapterId === null || value.chapterId === undefined) return null;
  if (typeof value.chapterId !== 'string') throw new TypeError('Expected chapterId string or null');
  return value.chapterId;
}

function safeColor(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 100) return null;
  const view = globalThis.CSS;
  return view?.supports('color', value) ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
