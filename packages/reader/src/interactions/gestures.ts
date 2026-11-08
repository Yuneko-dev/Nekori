import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

const OWNED_TARGETS = [
  'a[href]', 'button', 'input', 'select', 'textarea', 'label', 'summary', 'details',
  'video', 'audio', 'iframe', 'object', 'embed', '[onclick]', '[contenteditable=""]',
  '[contenteditable="true"]', '[role="button"]', '[role="link"]', '[role="checkbox"]',
  '[role="radio"]', '[role="switch"]', '[role="slider"]', '[role="spinbutton"]',
  '[role="tab"]', '[role="menuitem"]', '[role="option"]', '[role="combobox"]',
  '[role="textbox"]', '[role="dialog"]', '[aria-modal="true"]', '#nekori-image-modal',
  '#nekori-tts-controller', '#nekori-next-chapter', '#nekori-player-container',
  '#nekori-debug-overlay', '#nekori-debug-toggle', '.nekori-overlay', '.nekori-action',
  '.nekori-nextup', '#nekori-inline-feedback',
].join(',');

/** Install during document boot, before plugin scripts can stop propagation on owned controls. */
export function installGestureOwnership(host: ReaderHost): Unsubscribe {
  const document = host.root.ownerDocument;
  const isOwned = (node: EventTarget): boolean => node instanceof Element && node.matches(OWNED_TARGETS);
  const onPointerDown = (event: PointerEvent): void => {
    const target = event.target instanceof Element ? event.target : null;
    const ownsPath = event.composedPath().some(isOwned);
    const owner = !target || !event.isPrimary || target.closest(OWNED_TARGETS) || ownsPath
      ? 'blocked'
      : target.closest('img') ? 'image' : 'surface';
    host.emit('gesture.claim', { owner });
  };
  document.addEventListener('pointerdown', onPointerDown, true);
  host.emit('gesture.ready', {});
  return () => document.removeEventListener('pointerdown', onPointerDown, true);
}
