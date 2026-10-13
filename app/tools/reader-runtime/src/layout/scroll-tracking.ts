import { computeVerticalProgress, shouldLoadNextChapter, type ChapterBoundary } from './progress.ts';
import type { LayoutHost, ScrollTrackingConfig } from './types.ts';
import { measureChapterBoundaries } from './boundary-measurement.ts';

export interface ScrollTrackingController {
  configure(config: ScrollTrackingConfig): void;
  setLoadingNext(loading: boolean): void;
  setNoMoreChapters(value: boolean): void;
  resetChapterTracking(): void;
  updateChapterBoundaries(): void;
  dispose(): void;
}

export interface ScrollTrackingOptions {
  readonly root: HTMLElement;
  readonly host: LayoutHost;
  readonly isPaged?: () => boolean;
}

export function createScrollTracking({ root, host, isPaged = () => false }: ScrollTrackingOptions): ScrollTrackingController {
  const document = root.ownerDocument;
  const activeWindow = document.defaultView;
  if (!activeWindow) throw new Error('Scroll tracking requires an active document window');
  const view: Window & typeof globalThis = activeWindow;
  let config: ScrollTrackingConfig = {
    infiniteScrollEnabled: false,
    loadThreshold: 0.8,
    doneThreshold: 0.95,
  };
  let boundaries: ChapterBoundary[] = [];
  let loadingNext = false;
  let noMoreChapters = false;
  let lastVisibleChapterId: string | null = null;
  let lastSliderProgress = -1;
  let lastSliderUpdateAt = 0;
  let lastBodyResizeAt = 0;
  let frame: number | null = null;
  let boundaryFrame: number | null = null;
  let settleTimer: number | null = null;
  let rebuildPending = false;
  let disposed = false;
  const settleMs = 400;
  const scrollingElement = document.scrollingElement ?? document.documentElement;

  const scrollTop = (): number => view.scrollY || scrollingElement.scrollTop || document.body?.scrollTop || 0;
  const documentHeight = (): number => Math.max(scrollingElement.scrollHeight, document.body?.scrollHeight ?? 0);
  const viewportHeight = (): number => view.innerHeight || scrollingElement.clientHeight;
  const state = () => computeVerticalProgress(
    scrollTop(), documentHeight(), viewportHeight(), boundaries,
    config.infiniteScrollEnabled, config.doneThreshold,
  );

  function dispatchProgress(progress: ReturnType<typeof state>): void {
    view.dispatchEvent(new view.CustomEvent('nekori:progress', {
      detail: {
        progress: progress.progress,
        chapterProgress: progress.chapterProgress,
        chapterId: progress.chapterId,
        isLast: progress.isLast,
      },
    }));
  }

  function publishProgress(progress: ReturnType<typeof state>): void {
    if (Math.abs(progress.chapterProgress - lastSliderProgress) > 0.01 && Date.now() - lastSliderUpdateAt > 50) {
      lastSliderProgress = progress.chapterProgress;
      lastSliderUpdateAt = Date.now();
      host.emit('scroll.progressPreview', { progress: progress.chapterProgress });
      dispatchProgress(progress);
    }
    if (progress.isLast && progress.chapterProgress >= 1 && lastSliderProgress !== 1) {
      lastSliderProgress = 1;
      host.emit('scroll.progressPreview', { progress: 1 });
      host.emit('scroll.progressPersist', { progress: 1 });
      dispatchProgress(progress);
    }
  }

  function requestNextChapter(): void {
    if (loadingNext || !config.infiniteScrollEnabled || noMoreChapters) return;
    loadingNext = true;
    try {
      host.emit('navigation.loadNext', {});
    } catch {
      loadingNext = false;
    }
  }

  function sample(): void {
    frame = null;
    if (disposed || isPaged()) return;
    const current = state();
    if (config.infiniteScrollEnabled && current.chapterId !== null && current.chapterId !== lastVisibleChapterId) {
      lastVisibleChapterId = current.chapterId;
      host.emit('chapter.visible', { chapterId: current.chapterId, progress: current.chapterProgress });
    }
    publishProgress(current);
    if (shouldLoadNextChapter(current, boundaries, config.loadThreshold)) requestNextChapter();
  }

  function onScroll(): void {
    if (frame === null) frame = view.requestAnimationFrame(sample);
    if (settleTimer !== null) view.clearTimeout(settleTimer);
    settleTimer = view.setTimeout(persistCurrent, 250);
  }

  function persistCurrent(retriesLeft = 3): void {
    if (disposed || isPaged()) return;
    if (retriesLeft > 0 && Date.now() - lastBodyResizeAt < settleMs) {
      settleTimer = view.setTimeout(() => persistCurrent(retriesLeft - 1), settleMs);
      return;
    }
    const current = state();
    lastSliderProgress = current.chapterProgress;
    host.emit('scroll.progressPreview', { progress: current.chapterProgress });
    dispatchProgress(current);
    host.emit('scroll.progressPersist', { progress: current.chapterProgress });
  }

  const onScrollEnd = (): void => persistCurrent();

  function scheduleBoundaryRebuild(): void {
    if (!config.infiniteScrollEnabled || rebuildPending || disposed) return;
    rebuildPending = true;
    boundaryFrame = view.requestAnimationFrame(() => {
      boundaryFrame = null;
      rebuildPending = false;
      updateChapterBoundaries();
    });
  }

  function updateChapterBoundaries(): void {
    boundaries = measureChapterBoundaries(root);
    // Boundary changes can expose an already reachable short chapter without a scroll event.
    onScroll();
  }

  function onResize(): void {
    lastBodyResizeAt = Date.now();
    scheduleBoundaryRebuild();
  }

  view.addEventListener('scroll', onScroll, { passive: true });
  view.addEventListener('scrollend', onScrollEnd, { passive: true });
  const bodyResizeObserver = view.ResizeObserver && document.body
    ? new view.ResizeObserver(onResize)
    : null;
  bodyResizeObserver?.observe(document.body!);
  const boundaryResizeObserver = view.ResizeObserver && root
    ? new view.ResizeObserver(scheduleBoundaryRebuild)
    : null;
  boundaryResizeObserver?.observe(root);
  const mutationObserver = view.MutationObserver
    ? new view.MutationObserver(scheduleBoundaryRebuild)
    : null;
  mutationObserver?.observe(root, { childList: true, subtree: true });

  const controller: ScrollTrackingController = {
    configure(value) {
      config = {
        infiniteScrollEnabled: Boolean(value.infiniteScrollEnabled),
        loadThreshold: Math.min(Math.max(Number(value.loadThreshold) || 0, 0), 1),
        doneThreshold: Math.min(Math.max(Number(value.doneThreshold) || 0, 0), 1),
      };
      if (config.infiniteScrollEnabled) updateChapterBoundaries();
    },
    setLoadingNext(value) {
      loadingNext = Boolean(value);
      root.dataset.nekoriLoadingNext = String(loadingNext);
      if (!loadingNext) onScroll();
    },
    setNoMoreChapters(value) {
      noMoreChapters = Boolean(value);
      root.dataset.nekoriNoMoreChapters = String(noMoreChapters);
    },
    resetChapterTracking() {
      lastVisibleChapterId = null;
      onScroll();
    },
    updateChapterBoundaries,
    dispose() {
      disposed = true;
      view.removeEventListener('scroll', onScroll);
      view.removeEventListener('scrollend', onScrollEnd);
      bodyResizeObserver?.disconnect();
      boundaryResizeObserver?.disconnect();
      mutationObserver?.disconnect();
      if (frame !== null) view.cancelAnimationFrame(frame);
      if (boundaryFrame !== null) view.cancelAnimationFrame(boundaryFrame);
      if (settleTimer !== null) view.clearTimeout(settleTimer);
    },
  };
  return controller;
}
