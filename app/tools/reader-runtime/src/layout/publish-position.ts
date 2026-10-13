import { chapterForLeaf, leafRange, progressForUnit, unitCount } from './math.ts';
import type { MeasuredChapter } from './page-map.ts';
import type { LayoutConfig, LayoutHost, LayoutPosition } from './types.ts';

export interface PositionPublisherOptions {
  readonly host: LayoutHost;
  readonly getConfig: () => LayoutConfig;
  readonly getPageMap: () => readonly MeasuredChapter[];
  readonly getCurrentUnit: () => number;
  readonly perUnit: () => 1 | 2;
  readonly isSilent: () => boolean;
  readonly requestNext: (atBoundary: boolean) => void;
  readonly onProgress: (progress: number) => void;
}

export interface PositionPublisher {
  readonly position: LayoutPosition | null;
  readonly lastProgress: number | null;
  emitPosition(persist: boolean): void;
  setLastProgress(progress: number | null): void;
  resetPositionKey(): void;
}

export function createPositionPublisher(options: PositionPublisherOptions): PositionPublisher {
  const { host, getConfig, getPageMap, getCurrentUnit, perUnit, isSilent, requestNext, onProgress } = options;
  let lastChapterId: string | null = null;
  let lastPositionKey: string | null = null;
  let lastPosition: LayoutPosition | null = null;
  let lastChapterProgress: number | null = null;

  return {
    get position() { return lastPosition; },
    get lastProgress() { return lastChapterProgress; },
    setLastProgress(progress) { lastChapterProgress = progress; },
    resetPositionKey() { lastPositionKey = null; },
    emitPosition(persist) {
      const config = getConfig();
      const pageMap = getPageMap();
      if (!config.enabled || isSilent() || pageMap.length === 0) return;
      const globalUnit = getCurrentUnit();
      const globalLeaf = globalUnit * perUnit();
      const chapter = chapterForLeaf(pageMap, globalLeaf);
      if (!chapter) return;
      const localLeaf = Math.min(Math.max(globalLeaf - chapter.startLeaf, 0), chapter.leafCount - 1);
      const unitIndex = Math.floor(localLeaf / perUnit());
      const count = unitCount(chapter.leafCount, config.spread);
      const progress = progressForUnit(unitIndex, count);
      const range = leafRange(unitIndex, chapter.leafCount, config.spread);
      const positionKey = [chapter.chapterId, unitIndex, count, chapter.leafCount].join(':');
      const position: LayoutPosition = {
        chapterId: chapter.chapterId,
        unitIndex,
        unitCount: count,
        firstPage: range.firstLeaf,
        lastPage: range.lastLeaf,
        totalPages: chapter.leafCount,
        progress,
      };
      lastPosition = position;
      if (positionKey !== lastPositionKey) {
        lastPositionKey = positionKey;
        if (chapter.chapterId !== lastChapterId) host.emit('chapter.visible', { chapterId: chapter.chapterId, progress });
        host.emit('layout.positionChanged', position);
        host.emit('scroll.progressPreview', { progress });
      }
      if (persist) host.emit('scroll.progressPersist', { progress });
      if (config.infinite && chapter === pageMap.at(-1) && progress >= config.threshold) requestNext(false);
      lastChapterId = chapter.chapterId;
      lastChapterProgress = progress;
      onProgress(progress);
    },
  };
}
