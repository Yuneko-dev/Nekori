import { logicalOffset, physicalOffset, unitCount, unitFromOffset } from './math.ts';
import { measureChapterMap, type MeasuredChapter } from './page-map.ts';
import { createLayoutCommands, type LayoutTransitions } from './commands.ts';
import { createPositionPublisher } from './publish-position.ts';
import { performReflow } from './reflow.ts';
import { observeLayout } from './observers.ts';
import type { LayoutConfig, LayoutController, LayoutHost } from './types.ts';

const DEFAULT_CONFIG: LayoutConfig = {
  enabled: false,
  spread: 'single',
  direction: 'ltr',
  vertical: false,
  infinite: false,
  threshold: 0.8,
  chapterId: '',
};

export interface LayoutOptions {
  readonly root: HTMLElement;
  readonly host: LayoutHost;
}

export function createLayout({ root, host }: LayoutOptions): LayoutController {
  const document = root.ownerDocument;
  const activeWindow = document.defaultView;
  if (!activeWindow) throw new Error('Layout requires an active document window');
  const view: Window & typeof globalThis = activeWindow;
  let config = DEFAULT_CONFIG;
  let pageMap: MeasuredChapter[] = [];
  let totalLeaves = 1;
  let framePending = false;
  let reflowPending = false;
  const transitions: LayoutTransitions = {
    silent: false,
    restore: null,
    silentSourceUnit: null,
    lastChapterProgress: null,
  };
  let settleTimer: number | null = null;
  let animationFrame: number | null = null;
  let silentScrollTarget: number | null = null;

  const perUnit = (): 1 | 2 => (config.spread === 'double' ? 2 : 1);
  const viewport = (): number => Math.max(config.vertical ? root.clientHeight : root.clientWidth, 1);
  const extent = (): number => (config.vertical ? root.scrollHeight : root.scrollWidth);
  const readOffset = (): number => config.vertical
    ? root.scrollTop || 0
    : logicalOffset(root.scrollLeft || 0, config.direction);
  const writeOffset = (offset: number, behavior: ScrollBehavior = 'auto'): void => {
    if (config.vertical) root.scrollTo({ top: offset, behavior });
    else root.scrollTo({ left: physicalOffset(offset, config.direction), behavior });
  };
  const currentUnit = (): number => unitFromOffset(readOffset(), viewport(), unitCount(totalLeaves, config.spread));
  const scrollToUnit = (unit: number, behavior: ScrollBehavior = 'auto'): void => {
    const max = unitCount(totalLeaves, config.spread) - 1;
    writeOffset(Math.min(Math.max(unit, 0), max) * viewport(), behavior);
  };
  const measure = (): MeasuredChapter[] => {
    const map = measureChapterMap(root, readOffset(), viewport() / perUnit(), config.vertical, config.direction);
    map.forEach((chapter) => {
      observers.observe(chapter.element);
      chapter.element.querySelectorAll('nekori-chapter-summary').forEach((element) => observers.observe(element));
    });
    return map;
  };

  function requestNext(atBoundary: boolean): void {
    if (!config.infinite) {
      host.emit('navigation.loadNext', {});
      return;
    }
    if (root.dataset.nekoriLoadingNext === 'true' ||
        (root.dataset.nekoriNoMoreChapters === 'true' && !atBoundary)) return;
    root.dataset.nekoriLoadingNext = 'true';
    try {
      host.emit('navigation.loadNext', {});
    } catch {
      root.dataset.nekoriLoadingNext = 'false';
    }
  }

  const publisher = createPositionPublisher({
    host,
    getConfig: () => config,
    getPageMap: () => pageMap,
    getCurrentUnit: currentUnit,
    perUnit,
    isSilent: () => transitions.silent,
    requestNext,
    onProgress: (progress) => { transitions.lastChapterProgress = progress; },
  });
  const emitPosition = publisher.emitPosition;
  const resetPositionKey = publisher.resetPositionKey;

  function clampPageBounds(): void {
    const pageCount = unitCount(totalLeaves, config.spread);
    const offset = readOffset();
    const clamped = Math.min(Math.max(offset, 0), Math.max(0, (pageCount - 1) * viewport()));
    if (Math.abs(offset - clamped) > 0.5) writeOffset(clamped);
  }

  function onScroll(): void {
    clampPageBounds();
    if (!framePending) {
      framePending = true;
      animationFrame = view.requestAnimationFrame(() => {
        framePending = false;
        emitPosition(false);
      }) ?? null;
    }
    if (settleTimer !== null && view) view.clearTimeout(settleTimer);
    settleTimer = null;
    if (!view) return;
    if (transitions.silent) return;
    if (silentScrollTarget !== null) {
      if (Math.abs(readOffset() - silentScrollTarget) <= 1) return;
      silentScrollTarget = null;
    }
    settleTimer = view.setTimeout(() => {
      settleTimer = null;
      const unit = currentUnit();
      if (Math.abs(readOffset() - unit * viewport()) > 1) scrollToUnit(unit);
      emitPosition(true);
    }, 180);
  }

  function doReflow(): void {
    reflowPending = false;
    performReflow({
      root, config, pageMap, currentUnit: currentUnit(), perUnit: perUnit(), viewport: viewport(), extent,
      lastProgress: transitions.lastChapterProgress, restore: transitions.restore, measure, scrollToUnit,
      setPageMap: (value) => { pageMap = value; },
      setTotalLeaves: (value) => { totalLeaves = value; },
      clearRestore: () => { transitions.restore = null; },
      clearLastProgress: () => { transitions.lastChapterProgress = null; },
      resetPositionKey,
      emitPosition,
    });
  }

  function scheduleReflow(): void {
    if (!config.enabled || reflowPending) return;
    reflowPending = true;
    animationFrame = view.requestAnimationFrame(doReflow);
  }

  const observers = observeLayout(root, scheduleReflow);
  observers.observe(root);
  root.classList.add('nekori-reader');
  root.addEventListener('scroll', onScroll, { passive: true });

  const commands = createLayoutCommands({
    root,
    getConfig: () => config,
    getPageMap: () => pageMap,
    getTotalLeaves: () => totalLeaves,
    getCurrentUnit: currentUnit,
    perUnit,
    viewport,
    scrollToUnit,
    requestNext,
    scheduleReflow,
    emitPosition,
    onProgress: (progress) => { transitions.lastChapterProgress = progress; },
    transitions,
  });

  const controller: LayoutController = {
    get enabled() { return config.enabled; },
    get position() { return publisher.position; },
    configure(value) {
      config = {
        enabled: Boolean(value.enabled),
        vertical: Boolean(value.vertical),
        spread: !value.vertical && value.spread === 'double' ? 'double' : 'single',
        direction: value.direction === 'rtl' ? 'rtl' : 'ltr',
        infinite: Boolean(value.infinite),
        chapterId: value.chapterId,
        threshold: Number.isFinite(value.threshold) ? Math.min(Math.max(value.threshold, 0), 1) : 0.8,
      };
      root.classList.toggle('is-paged', config.enabled);
      root.dataset.readerSpread = config.spread;
      root.dataset.readerWritingMode = config.vertical ? 'vertical-rl' : 'horizontal-tb';
      if (config.enabled) root.dir = config.vertical ? 'ltr' : config.direction;
      else root.removeAttribute('dir');
      if (config.enabled) scheduleReflow();
      else root.querySelectorAll('.nekori-layout-blank-leaf').forEach((blank) => blank.remove());
    },
    ...commands,
    prepareSilentTurn(preserveViewport = true) {
      if (settleTimer !== null) view.clearTimeout(settleTimer);
      settleTimer = null;
      return commands.prepareSilentTurn(preserveViewport);
    },
    finishSilentTurn(reflow = true, commit = true) {
      if (settleTimer !== null) view.clearTimeout(settleTimer);
      settleTimer = null;
      const finished = commands.finishSilentTurn(reflow, commit);
      silentScrollTarget = readOffset();
      return finished;
    },
    dispose() {
      root.removeEventListener('scroll', onScroll);
      observers.dispose();
      if (settleTimer !== null) view?.clearTimeout(settleTimer);
      if (animationFrame !== null) view?.cancelAnimationFrame(animationFrame);
    },
  };

  return controller;
}
