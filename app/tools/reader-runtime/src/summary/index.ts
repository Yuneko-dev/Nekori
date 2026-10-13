import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';
import type { ReaderLayout } from '../protocol/reader-api.ts';
import { findChapter } from '../interactions/selection.ts';
import summaryCss from '../styles/summary.css?inline';

const HOST_TAG = 'nekori-chapter-summary';
export type SummaryState = 'loading' | 'ready' | 'failed' | 'cancelled';

interface SummaryLabels {
  title: string;
  loading: string;
  cancel: string;
  regenerate: string;
  close: string;
}

export function registerSummaryCommands(host: ReaderHost, layout: ReaderLayout | null): Unsubscribe[] {
  return [
    host.onCommand('summary.focus', (value) => {
      const chapterId = readChapterId(value);
      const card = existingSummary(host.root, chapterId);
      if (!card) return false;
      if (layout?.enabled) layout.revealElement(card);
      else card.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return true;
    }),
    host.onCommand('summary.render', (value) => renderSummary(host, value)),
    host.onCommand('summary.remove', (value) => existingSummary(host.root, readChapterId(value))?.remove()),
  ];
}

function renderSummary(host: ReaderHost, value: unknown): void {
  if (!isRecord(value) || typeof value.chapterId !== 'string' ||
      (value.state !== 'loading' && value.state !== 'ready' && value.state !== 'failed' && value.state !== 'cancelled') ||
      typeof value.text !== 'string' || !isLabels(value.labels)) {
    throw new TypeError('Invalid chapter summary payload');
  }
  const chapter = findChapter(host.root, value.chapterId);
  if (!chapter) return;
  const card = existingSummary(host.root, value.chapterId) ?? createSummary(host.root.ownerDocument, chapter, value.chapterId);
  const shadow = card.shadowRoot;
  if (!shadow) throw new Error('Summary shadow root is unavailable');
  shadow.querySelector<HTMLElement>('.title')!.textContent = value.labels.title;
  const body = shadow.querySelector<HTMLElement>('.body')!;
  const actions = shadow.querySelector<HTMLElement>('.actions')!;
  body.className = value.state === 'ready' ? 'body' : 'body status';
  if (value.state === 'ready') renderParagraphs(body, value.text);
  else body.textContent = value.state === 'loading' ? value.labels.loading : value.text;
  actions.replaceChildren();
  actions.append(createAction(host, value.chapterId, value.state === 'loading' ? value.labels.cancel : value.labels.regenerate,
    value.state === 'loading' ? 'cancel' : 'regenerate'));
  actions.append(createAction(host, value.chapterId, value.labels.close, 'close'));
  if (value.state === 'failed' && value.text) host.emit('reader.error', { message: value.text });
}

function createSummary(document: Document, chapter: HTMLElement, chapterId: string): HTMLElement {
  const host = document.createElement(HOST_TAG);
  host.setAttribute('data-nekori-chapter-id', chapterId);
  const shadow = host.attachShadow({ mode: 'open' });
  const stylesheet = document.createElement('style');
  stylesheet.textContent = summaryCss;
  const card = document.createElement('div');
  card.className = 'card';
  const heading = document.createElement('div');
  heading.className = 'head';
  const title = document.createElement('span');
  title.className = 'title';
  heading.append(title);
  const body = document.createElement('div');
  body.className = 'body';
  const actions = document.createElement('div');
  actions.className = 'actions';
  card.append(heading, body, actions);
  shadow.append(stylesheet, card);
  chapter.prepend(host);
  return host;
}

function createAction(host: ReaderHost, chapterId: string, label: string, action: 'cancel' | 'close' | 'regenerate'): HTMLButtonElement {
  const button = host.root.ownerDocument.createElement('button');
  button.type = 'button';
  button.textContent = label;
  button.addEventListener('click', () => host.emit('summary.action', { chapterId, action }));
  return button;
}

function renderParagraphs(container: HTMLElement, text: string): void {
  const fragment = container.ownerDocument.createDocumentFragment();
  for (const block of text.split(/\n\s*\n/)) {
    const content = block.trim();
    if (!content) continue;
    const paragraph = container.ownerDocument.createElement('p');
    paragraph.textContent = content;
    fragment.append(paragraph);
  }
  container.replaceChildren(fragment);
  if (!container.firstChild) container.textContent = text.trim();
}

function existingSummary(root: ParentNode, chapterId: string): HTMLElement | null {
  const chapter = findChapter(root, chapterId);
  return chapter?.querySelector<HTMLElement>(`:scope > ${HOST_TAG}`) ?? null;
}

function readChapterId(value: unknown): string {
  if (!isRecord(value) || typeof value.chapterId !== 'string') throw new TypeError('Expected chapterId string');
  return value.chapterId;
}

function isLabels(value: unknown): value is SummaryLabels {
  return isRecord(value) && typeof value.title === 'string' && typeof value.loading === 'string' &&
    typeof value.cancel === 'string' && typeof value.regenerate === 'string' && typeof value.close === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
