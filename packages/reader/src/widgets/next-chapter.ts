import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

interface Labels { hasNext: boolean; finishedText: string; nextText: string; noNextText: string }

export function registerNextChapterCommand(host: ReaderHost): Unsubscribe {
  let labels: Labels | null = null;
  const render = (): void => {
    host.root.ownerDocument.querySelector('#nekori-next-chapter')?.remove();
    if (!labels) return;
    const document = host.root.ownerDocument;
    const container = document.createElement('div');
    container.id = 'nekori-next-chapter';
    const finished = van.tags.div({ class: 'info-text' }, labels.finishedText);
    container.append(finished);
    if (labels.hasNext) {
      const button = van.tags.button({ class: 'next-button', type: 'button' }, labels.nextText);
      button.addEventListener('click', (event) => {
        event.stopPropagation();
        host.emit('navigation.openNext', {});
      });
      container.append(button);
    } else {
      const message = van.tags.div({ class: 'info-text' }, labels.noNextText);
      container.append(message);
    }
    (document.getElementById('reader-ui') ?? document.body).append(container);
  };
  return host.onCommand('ui.configureNextChapter', (value) => {
    if (!isRecord(value) || typeof value.hasNext !== 'boolean' || typeof value.finishedText !== 'string' || typeof value.nextText !== 'string' ||
        typeof value.noNextText !== 'string') throw new TypeError('Invalid next chapter labels');
    labels = { hasNext: value.hasNext, finishedText: value.finishedText, nextText: value.nextText, noNextText: value.noNextText };
    render();
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
import van from 'vanjs-core';
