export function observeImageLoading(root: HTMLElement): () => void {
  const window = root.ownerDocument.defaultView;
  if (!window) throw new Error('Image loading observer requires an active document window');
  const markLoaded = (image: HTMLImageElement): void => {
    if (image.complete && image.naturalWidth > 0) image.classList.add('is-loaded');
    else image.addEventListener('load', () => image.classList.add('is-loaded'), { once: true });
  };
  const setup = (node: Element): void => {
    if (node.matches('img')) markLoaded(node as HTMLImageElement);
    node.querySelectorAll('img').forEach((image) => markLoaded(image));
  };
  root.querySelectorAll('img').forEach((image) => markLoaded(image));
  const observer = new window.MutationObserver((records) => {
    for (const record of records) for (const node of record.addedNodes) {
      if (node instanceof window.Element) setup(node);
    }
  });
  observer.observe(root, { childList: true, subtree: true });
  return () => observer.disconnect();
}
