export type Spread = 'single' | 'double';
export type Direction = 'ltr' | 'rtl';

export interface ChapterLeafRange {
  readonly chapterId: string;
  readonly startLeaf: number;
  readonly leafCount: number;
}

export interface LeafRange {
  readonly firstLeaf: number;
  readonly lastLeaf: number;
}

const clamp = (value: number, low: number, high: number): number =>
  Math.min(Math.max(value, low), high);

export function leavesPerUnit(spread: Spread): 1 | 2 {
  return spread === 'double' ? 2 : 1;
}

export function unitCount(leafCount: number, spread: Spread): number {
  return Math.max(1, Math.ceil(Math.max(0, leafCount) / leavesPerUnit(spread)));
}

export function unitFromOffset(offset: number, viewport: number, count: number): number {
  if (!(viewport > 0) || count < 2) return 0;
  return clamp(Math.round(Math.max(0, offset) / viewport), 0, count - 1);
}

export function unitFromProgress(progress: number, count: number): number {
  if (count < 2) return 0;
  return clamp(Math.round(clamp(progress, 0, 1) * (count - 1)), 0, count - 1);
}

export function progressForUnit(unit: number, count: number): number {
  if (count < 2) return 1;
  return clamp(unit, 0, count - 1) / (count - 1);
}

export function leafRange(unit: number, leafCount: number, spread: Spread): LeafRange {
  const perUnit = leavesPerUnit(spread);
  const first = clamp(unit, 0, unitCount(leafCount, spread) - 1) * perUnit;
  return {
    firstLeaf: first + 1,
    lastLeaf: Math.min(first + perUnit, Math.max(1, leafCount)),
  };
}

export function logicalOffset(scrollLeft: number, direction: Direction): number {
  return direction === 'rtl' ? -scrollLeft : scrollLeft;
}

export function physicalOffset(offset: number, direction: Direction): number {
  return direction === 'rtl' ? -offset : offset;
}

export function chapterForLeaf<T extends ChapterLeafRange>(chapters: readonly T[], leaf: number): T | null {
  if (chapters.length === 0) return null;
  let match = chapters[0] ?? null;
  for (const chapter of chapters) {
    if (leaf < chapter.startLeaf) break;
    match = chapter;
    if (leaf < chapter.startLeaf + chapter.leafCount) break;
  }
  return match;
}
