import { chapterForLeaf, unitCount, unitFromProgress } from './math.ts';
import { rebuildBlankLeaves, type MeasuredChapter } from './page-map.ts';
import type { LayoutConfig } from './types.ts';
import type { RestorePoint } from './commands.ts';
import { combineShortNumbers } from './vertical-text.ts';

export interface ReflowOptions {
  readonly root: HTMLElement;
  readonly config: LayoutConfig;
  readonly pageMap: readonly MeasuredChapter[];
  readonly currentUnit: number;
  readonly perUnit: 1 | 2;
  readonly viewport: number;
  readonly extent: () => number;
  readonly lastProgress: number | null;
  readonly restore: RestorePoint | null;
  readonly measure: () => MeasuredChapter[];
  readonly scrollToUnit: (unit: number) => void;
  readonly setPageMap: (chapters: MeasuredChapter[]) => void;
  readonly setTotalLeaves: (count: number) => void;
  readonly clearRestore: () => void;
  readonly clearLastProgress: () => void;
  readonly resetPositionKey: () => void;
  readonly emitPosition: (persist: boolean) => void;
}

export function performReflow(options: ReflowOptions): void {
  const {
    root, config, pageMap, currentUnit, perUnit, viewport, extent, lastProgress, restore,
    measure, scrollToUnit, setPageMap, setTotalLeaves, clearRestore, clearLastProgress,
    resetPositionKey, emitPosition,
  } = options;
  if (!config.enabled) return;
  const currentChapter = chapterForLeaf(pageMap, currentUnit * perUnit);
  const snapshot: RestorePoint | null = restore ?? (currentChapter
    ? { chapterId: currentChapter.chapterId, progress: lastProgress ?? 0 }
    : null);
  if (config.vertical) combineShortNumbers(root);
  const beforeBlanks = measure();
  rebuildBlankLeaves(root, beforeBlanks, config.spread === 'double');
  let measured = measure();
  const leafSize = viewport / perUnit;
  const contentLeaves = Math.max(1, Math.round(extent() / leafSize));
  if (measured.length === 0 && config.spread === 'double' && contentLeaves % 2 !== 0) {
    const blank = root.ownerDocument.createElement('div');
    blank.className = 'nekori-layout-blank-leaf';
    blank.setAttribute('aria-hidden', 'true');
    root.append(blank);
  }
  setTotalLeaves(Math.max(1, Math.round(extent() / leafSize)));
  if (measured.length === 0) {
    measured = [{ chapterId: config.chapterId, startLeaf: 0, leafCount: contentLeaves, element: root }];
  }
  setPageMap(measured);
  if (snapshot) {
    const chapter = measured.find((entry) => entry.chapterId === snapshot.chapterId);
    if (chapter) {
      const count = unitCount(chapter.leafCount, config.spread);
      const local = snapshot.unitIndex === undefined
        ? unitFromProgress(snapshot.progress, count)
        : Math.min(Math.max(snapshot.unitIndex, 0), count - 1);
      scrollToUnit(Math.floor(chapter.startLeaf / perUnit) + local);
    }
  }
  clearRestore();
  if (restore) clearLastProgress();
  resetPositionKey();
  emitPosition(false);
}
