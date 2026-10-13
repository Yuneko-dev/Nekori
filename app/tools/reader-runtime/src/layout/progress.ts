export interface ChapterBoundary {
  readonly chapterId: string;
  readonly startOffset: number;
  readonly height: number;
}

export interface VerticalProgress {
  readonly progress: number;
  readonly chapterProgress: number;
  readonly boundaryIndex: number;
  readonly chapterId: string | null;
  readonly isLast: boolean;
}

export function computeVerticalProgress(
  scrollTop: number,
  documentHeight: number,
  viewportHeight: number,
  boundaries: readonly ChapterBoundary[],
  enabled: boolean,
  doneThreshold: number,
): VerticalProgress {
  const scrollable = documentHeight - viewportHeight;
  let progress = scrollable > 0 ? scrollTop / scrollable : 1;
  if (scrollable > 0 && scrollTop >= scrollable - 2) progress = 1;
  if (progress >= doneThreshold) progress = 1;
  progress = Math.max(progress, 0);

  let chapterProgress = progress;
  let boundaryIndex = 0;
  let chapterId: string | null = null;
  let isLast = true;
  if (enabled && boundaries.length > 1) {
    for (let index = 0; index < boundaries.length; index += 1) {
      const boundary = boundaries[index];
      if (boundary && scrollTop >= boundary.startOffset) boundaryIndex = index;
      else break;
    }
    if (scrollable <= 0 || scrollTop >= scrollable - 2) boundaryIndex = boundaries.length - 1;

    const boundary = boundaries[boundaryIndex];
    if (boundary) {
      chapterId = boundary.chapterId;
      const chapterScrollTop = Math.max(scrollTop - boundary.startOffset, 0);
      isLast = boundaryIndex === boundaries.length - 1;
      if (isLast && boundary.height <= viewportHeight) {
        chapterProgress = progress;
      } else {
        const effectiveHeight = Math.max(boundary.height - (isLast ? viewportHeight : 0), 1);
        chapterProgress = Math.min(chapterScrollTop / effectiveHeight, 1);
        if (chapterProgress >= doneThreshold) chapterProgress = 1;
      }
    }
  }

  return { progress, chapterProgress, boundaryIndex, chapterId, isLast };
}

export function shouldLoadNextChapter(
  state: VerticalProgress,
  boundaries: readonly ChapterBoundary[],
  threshold: number,
): boolean {
  return boundaries.length > 1
    ? state.boundaryIndex === boundaries.length - 1 && state.chapterProgress >= threshold
    : state.progress >= threshold;
}

export function boundariesFromOffsets(
  chapterIds: readonly string[],
  offsets: readonly number[],
  documentEnd: number,
): ChapterBoundary[] {
  return chapterIds.map((chapterId, index) => {
    const startOffset = offsets[index] ?? 0;
    const endOffset = offsets[index + 1] ?? documentEnd;
    return { chapterId, startOffset, height: endOffset - startOffset };
  });
}
