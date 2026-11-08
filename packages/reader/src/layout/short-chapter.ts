import type { LayoutHost } from './types.ts';

export interface ObserveShortChapterOptions {
  readonly host: LayoutHost & { readonly root: HTMLElement };
  readonly enabled: boolean;
}

/** Marks an initially non-scrollable, non-paged chapter after its first layout settles. */
export function observeShortChapter({ host, enabled }: ObserveShortChapterOptions): () => void {
  if (!enabled || host.root.classList.contains('is-paged')) return () => {};
  const document = host.root.ownerDocument;
  const view = document.defaultView;
  if (!view) return () => {};

  let called = false;
  let disposed = false;
  let timeout: number | null = null;
  const check = (): boolean => {
    const documentHeight = Math.max(
      document.documentElement.scrollHeight,
      document.body?.scrollHeight ?? 0,
    );
    const viewport = view.innerHeight || document.documentElement.clientHeight;
    if (documentHeight - viewport > 0) return false;
    called = true;
    host.emit('chapter.markShort', {});
    observer?.disconnect();
    if (timeout !== null) view.clearTimeout(timeout);
    timeout = null;
    return true;
  };
  const observer = view.ResizeObserver && document.body
    ? new view.ResizeObserver(() => { if (!called && !disposed) check(); })
    : null;
  observer?.observe(document.body!);
  timeout = view.setTimeout(() => {
    if (!called && !disposed) check();
    observer?.disconnect();
    timeout = null;
  }, 500);

  return () => {
    disposed = true;
    observer?.disconnect();
    if (timeout !== null) view.clearTimeout(timeout);
    timeout = null;
  };
}
