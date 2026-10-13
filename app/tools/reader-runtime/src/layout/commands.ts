import { chapterForLeaf, logicalOffset, progressForUnit, unitCount, unitFromProgress } from './math.ts';
import { leafForRect, type MeasuredChapter } from './page-map.ts';
import type { LayoutConfig, LayoutController } from './types.ts';

export interface RestorePoint {
  readonly chapterId: string;
  readonly progress: number;
  readonly unitIndex?: number;
}

export interface LayoutTransitions {
  silent: boolean;
  restore: RestorePoint | null;
  silentSourceUnit: number | null;
  lastChapterProgress: number | null;
}

export interface LayoutCommandOptions {
  readonly root: HTMLElement;
  readonly getConfig: () => LayoutConfig;
  readonly getPageMap: () => readonly MeasuredChapter[];
  readonly getTotalLeaves: () => number;
  readonly getCurrentUnit: () => number;
  readonly perUnit: () => 1 | 2;
  readonly viewport: () => number;
  readonly scrollToUnit: (unit: number, behavior?: ScrollBehavior) => void;
  readonly requestNext: (atBoundary: boolean) => void;
  readonly scheduleReflow: () => void;
  readonly emitPosition: (persist: boolean) => void;
  readonly onProgress: (progress: number) => void;
  readonly transitions: LayoutTransitions;
}

type LayoutCommands = Pick<LayoutController,
  'moveBy' | 'seekUnit' | 'seekPercent' | 'revealElement' | 'reflow' | 'prepareSilentTurn' | 'finishSilentTurn'>;

export function createLayoutCommands(options: LayoutCommandOptions): LayoutCommands {
  const {
    root, getConfig, getPageMap, getTotalLeaves, getCurrentUnit, perUnit, viewport,
    scrollToUnit, requestNext, scheduleReflow, emitPosition, onProgress, transitions,
  } = options;

  function rememberLeafProgress(leaf: number): void {
    const config = getConfig();
    const chapter = chapterForLeaf(getPageMap(), leaf);
    if (!chapter) return;
    const localLeaf = Math.min(Math.max(leaf - chapter.startLeaf, 0), chapter.leafCount - 1);
    const localUnit = Math.floor(localLeaf / perUnit());
    onProgress(progressForUnit(localUnit, unitCount(chapter.leafCount, config.spread)));
  }

  return {
    moveBy(delta, behavior = 'auto') {
      const source = transitions.silentSourceUnit ?? getCurrentUnit();
      const destination = source + Math.sign(Number(delta || 0));
      if (destination < 0) return false;
      if (destination >= unitCount(getTotalLeaves(), getConfig().spread)) {
        requestNext(true);
        return false;
      }
      rememberLeafProgress(destination * perUnit());
      scrollToUnit(destination, behavior);
      return true;
    },
    seekUnit(localUnit, behavior = 'auto') {
      const config = getConfig();
      const chapter = chapterForLeaf(getPageMap(), getCurrentUnit() * perUnit());
      if (!chapter) return false;
      const count = unitCount(chapter.leafCount, config.spread);
      const target = Math.min(Math.max(Number(localUnit) || 0, 0), count - 1);
      const destination = Math.floor(chapter.startLeaf / perUnit()) + target;
      rememberLeafProgress(destination * perUnit());
      scrollToUnit(destination, behavior);
      return true;
    },
    seekPercent(percent) {
      const config = getConfig();
      const chapter = chapterForLeaf(getPageMap(), getCurrentUnit() * perUnit());
      if (!chapter) return false;
      const count = unitCount(chapter.leafCount, config.spread);
      const local = unitFromProgress(Number(percent) / 100, count);
      transitions.lastChapterProgress = progressForUnit(local, count);
      onProgress(transitions.lastChapterProgress);
      const destination = Math.floor(chapter.startLeaf / perUnit()) + local;
      rememberLeafProgress(destination * perUnit());
      scrollToUnit(destination);
      return true;
    },
    revealElement(element) {
      if (!element?.getBoundingClientRect) return false;
      const config = getConfig();
      const leaf = leafForRect(
        element.getBoundingClientRect(),
        root.getBoundingClientRect(),
        config.vertical ? root.scrollTop || 0 : logicalOffset(root.scrollLeft || 0, config.direction),
        viewport() / perUnit(),
        config.vertical,
        config.direction,
      );
      const destination = Math.floor(leaf / perUnit());
      rememberLeafProgress(destination * perUnit());
      scrollToUnit(destination);
      return true;
    },
    reflow(preserveViewport = false) {
      if (preserveViewport && getConfig().enabled && !transitions.restore) {
        const currentUnit = getCurrentUnit();
        const chapter = chapterForLeaf(getPageMap(), currentUnit * perUnit());
        if (chapter) {
          // Ephemeral cards change chapter length, while the text before the viewport stays in place.
          transitions.restore = {
            chapterId: chapter.chapterId,
            progress: transitions.lastChapterProgress ?? 0,
            unitIndex: currentUnit - Math.floor(chapter.startLeaf / perUnit()),
          };
        }
      }
      scheduleReflow();
      return true;
    },
    prepareSilentTurn(preserveViewport = true) {
      const chapter = chapterForLeaf(getPageMap(), getCurrentUnit() * perUnit());
      transitions.silentSourceUnit = getCurrentUnit();
      transitions.restore = preserveViewport && chapter
        ? { chapterId: chapter.chapterId, progress: transitions.lastChapterProgress ?? 0 }
        : null;
      transitions.silent = true;
      return true;
    },
    finishSilentTurn(reflow = true, commit = true) {
      if (!commit && transitions.silentSourceUnit !== null) scrollToUnit(transitions.silentSourceUnit);
      transitions.silentSourceUnit = null;
      transitions.silent = false;
      if (!reflow) emitPosition(commit);
      else scheduleReflow();
      return true;
    },
  };
}
