export interface ImageModal {
  close(): void;
  setCloseLabel(label: string): void;
  dispose(): void;
}

export function createImageModal(root: HTMLElement, closeLabel: string): ImageModal {
  const document = root.ownerDocument;
  const window = document.defaultView;
  if (!window) throw new Error('Image modal requires an active document window');
  const image = van.tags.img({ id: 'nekori-image-modal-image' });
  const container = van.tags.div({
    id: 'nekori-image-modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': closeLabel,
  }, image);
  (document.getElementById('reader-ui') ?? document.body).append(container);

  let previousViewport: string | null = null;
  const viewportMeta = (): HTMLMetaElement => {
    let meta = document.querySelector<HTMLMetaElement>('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
      document.head.append(meta);
    }
    return meta;
  };
  const close = (): void => {
    if (!container.classList.contains('show')) return;
    container.classList.remove('show');
    image.removeAttribute('src');
    const meta = viewportMeta();
    if (previousViewport === null) meta.removeAttribute('content');
    else meta.setAttribute('content', previousViewport);
    previousViewport = null;
  };
  const open = (source: HTMLImageElement): void => {
    const src = source.currentSrc || source.src;
    if (!src) return;
    previousViewport = viewportMeta().getAttribute('content');
    viewportMeta().setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=10');
    image.src = src;
    image.alt = source.alt;
    container.classList.add('show');
  };
  const onContextMenu = (event: MouseEvent): void => {
    if (!(event.target instanceof HTMLImageElement)) return;
    event.preventDefault();
    if (container.classList.contains('show')) close();
    else if (event.target !== image) open(event.target);
  };
  const onClick = (event: MouseEvent): void => {
    if (event.target !== image) {
      event.stopPropagation();
      close();
    }
  };
  const onKeyDown = (event: KeyboardEvent): void => {
    if (event.key === 'Escape') close();
  };
  container.addEventListener('click', onClick);
  document.addEventListener('contextmenu', onContextMenu);
  window.addEventListener('keydown', onKeyDown);
  return {
    close,
    setCloseLabel(label) { container.setAttribute('aria-label', label); },
    dispose() {
      close();
      container.removeEventListener('click', onClick);
      document.removeEventListener('contextmenu', onContextMenu);
      window.removeEventListener('keydown', onKeyDown);
      container.remove();
    },
  };
}
import van from 'vanjs-core';

