import van from 'vanjs-core';
import type { ReaderHost, ReaderLayout } from '../protocol/reader-api.ts';
import feedbackCss from '../styles/feedback-card.css?inline';

const FEEDBACK_TAG = 'nekori-reader-feedback';

export interface InlineFeedback {
  show(payload: unknown): void;
  clear(): void;
  dispose(): void;
}

type Severity = 'error' | 'info';
type Presentation = 'fullpage' | 'boundary';

interface FeedbackPayload extends Record<string, unknown> {
  severity: Severity;
  presentation: Presentation;
}

export function createInlineFeedback(root: HTMLElement, host: ReaderHost, layout: ReaderLayout | null): InlineFeedback {
  const document = root.ownerDocument;
  let current: HTMLElement | null = null;

  const reflow = (): void => { layout?.reflow(true); };
  const clear = (): void => {
    if (!current) return;
    current.remove();
    current = null;
    reflow();
  };

  const show = (value: unknown): void => {
    if (!isRecord(value)) throw new TypeError('Invalid reader feedback payload');
    clear();
    const payload = normalizePayload(value);
    const chapter = payload.presentation === 'boundary'
      ? resolveBoundary(root, host, payload.chapterId)
      : null;
    const feedbackHost = createFeedbackHost(document, payload, host, reflow, () => {
      if (current === feedbackHost) {
        feedbackHost.remove();
        current = null;
        reflow();
      }
    });
    current = feedbackHost;
    (chapter ?? root).append(feedbackHost);
    reflow();
  };

  return { show, clear, dispose: clear };
}

function createFeedbackHost(
  document: Document,
  payload: FeedbackPayload,
  host: ReaderHost,
  reflow: () => void,
  dismiss: () => void,
): HTMLElement {
  const feedbackHost = document.createElement(FEEDBACK_TAG);
  feedbackHost.dataset.severity = payload.severity;
  feedbackHost.dataset.presentation = payload.presentation;
  for (const [name, value] of Object.entries({
    display: 'block',
    position: 'static',
    zIndex: 'auto',
    inlineSize: 'auto',
    maxInlineSize: '100%',
    breakInside: 'avoid',
    pageBreakInside: 'avoid',
  })) feedbackHost.style.setProperty(name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`), value, 'important');
  const shadow = feedbackHost.attachShadow({ mode: 'open' });
  const style = van.tags.style(feedbackCss);
  const card = van.tags.section({
    class: 'card',
    role: payload.severity === 'error' ? 'alert' : 'status',
    'aria-live': payload.severity === 'error' ? 'assertive' : 'polite',
  });
  const category = optionalString(payload.category ?? payload.title);
  const summary = optionalString(payload.summary ?? payload.message) ?? '';
  const title = category ?? (payload.severity === 'info' ? summary : null);
  if (title) card.append(van.tags.h2({ class: 'category' }, title));
  if (summary && (category || payload.severity !== 'info')) {
    card.append(van.tags.p({ class: 'summary' }, summary));
  }

  const trace = decodeBase64(document.defaultView, payload.traceBase64);
  if (trace !== null) {
    const details = van.tags.details({ class: 'details' });
    const disclosure = van.tags.summary({}, optionalString(payload.detailsLabel) ?? 'Details');
    const pre = van.tags.pre({ class: 'trace' }, van.tags.code({}, trace));
    details.open = payload.presentation === 'fullpage';
    details.append(disclosure, pre);
    details.addEventListener('toggle', reflow);
    card.append(details);
  }

  const actions = van.tags.div({ class: 'actions' });
  const dismissLabel = optionalString(payload.dismissLabel);
  if (payload.presentation === 'boundary' || dismissLabel !== null) {
    actions.append(makeButton(document, dismissLabel ?? 'Dismiss', 'dismiss', dismiss));
  }
  if (typeof payload.retryLabel === 'string') {
    actions.append(makeButton(document, payload.retryLabel, 'retry', () => {
      dismiss();
      host.emit('feedback.retry', {});
    }));
  }
  if (trace !== null && typeof payload.copyLabel === 'string') {
    actions.append(makeButton(document, payload.copyLabel, 'copy', () => host.emit('clipboard.copy', { text: trace })));
  }
  if (actions.childElementCount > 0) card.append(actions);
  shadow.append(style, card);
  return feedbackHost;
}

function makeButton(document: Document, label: string, action: string, onClick: () => void): HTMLButtonElement {
  const button = van.tags.button({ type: 'button', class: `action action-${action}` }, label);
  button.addEventListener('click', onClick);
  return button;
}

function resolveBoundary(root: HTMLElement, host: ReaderHost, requestedId: unknown): HTMLElement {
  const chapters = getChapters(root);
  const requested = typeof requestedId === 'string' ? chapters.find((chapter) => chapter.dataset.nekoriChapterId === requestedId) : undefined;
  if (requested) return requested;
  const current = chapters.find((chapter) => chapter.dataset.nekoriChapterId === host.chapter.id);
  return current ?? chapters.at(-1) ?? root;
}

function getChapters(root: HTMLElement): HTMLElement[] {
  const ownChapter = root.matches('nekori-chapter') ? [root] : [];
  return [...ownChapter, ...root.querySelectorAll<HTMLElement>('nekori-chapter')];
}

function normalizePayload(value: Record<string, unknown>): FeedbackPayload {
  const severity: Severity = value.severity === 'info' ? 'info' : 'error';
  const presentation: Presentation = value.presentation === 'boundary' ? 'boundary' : 'fullpage';
  return { ...value, severity, presentation };
}

function decodeBase64(view: Window | null, encoded: unknown): string | null {
  if (!view || typeof encoded !== 'string') return null;
  try {
    const binary = view.atob(encoded);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    return new TextDecoder('utf-8').decode(bytes);
  } catch {
    return null;
  }
}

function optionalString(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
