export interface LayoutObservers {
  observe(element: Element): void;
  dispose(): void;
}

export function observeLayout(root: HTMLElement, scheduleReflow: () => void): LayoutObservers {
  const document = root.ownerDocument;
  const activeWindow = document.defaultView;
  if (!activeWindow) throw new Error('Layout observers require an active document window');
  const view: Window & typeof globalThis = activeWindow;
  const observed = new WeakSet<Element>();
  const resizeObserver = view.ResizeObserver ? new view.ResizeObserver(scheduleReflow) : null;
  const mutationObserver = view.MutationObserver
    ? new view.MutationObserver((mutations) => {
        const external = mutations.some((mutation) =>
          [...mutation.addedNodes, ...mutation.removedNodes].some((node) =>
            node.nodeType !== 1 || !(node as Element).classList.contains('nekori-layout-blank-leaf'),
          ),
        );
        if (external) scheduleReflow();
      })
    : null;
  const onResourceLoad = (event: Event): void => {
    if (event.target instanceof view.HTMLImageElement) scheduleReflow();
  };
  const fonts = document.fonts;
  mutationObserver?.observe(root, { childList: true, subtree: true });
  document.addEventListener('load', onResourceLoad, true);
  void fonts?.ready.then(scheduleReflow);
  fonts?.addEventListener('loadingdone', scheduleReflow);

  return {
    observe(element) {
      if (!resizeObserver || observed.has(element)) return;
      resizeObserver.observe(element);
      observed.add(element);
    },
    dispose() {
      document.removeEventListener('load', onResourceLoad, true);
      fonts?.removeEventListener('loadingdone', scheduleReflow);
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
    },
  };
}
