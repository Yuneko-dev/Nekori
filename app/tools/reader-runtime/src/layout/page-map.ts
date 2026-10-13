import type { ChapterLeafRange } from './math.ts';

export interface MeasuredChapter extends ChapterLeafRange {
  readonly element: HTMLElement;
}

export function leafForRect(
  rect: DOMRect,
  hostRect: DOMRect,
  offset: number,
  leafSize: number,
  vertical: boolean,
  direction: 'ltr' | 'rtl',
): number {
  const position = vertical
    ? rect.top - hostRect.top + offset
    : direction === 'rtl'
      ? hostRect.right - rect.right + offset
      : rect.left - hostRect.left + offset;
  return Math.max(0, Math.floor((position + 1) / leafSize));
}

export function measureChapterMap(
  root: HTMLElement,
  offset: number,
  leafSize: number,
  vertical: boolean,
  direction: 'ltr' | 'rtl',
): MeasuredChapter[] {
  const hostRect = root.getBoundingClientRect();
  return Array.from(root.querySelectorAll<HTMLElement>('.nekori-chapter')).map((element) => {
    let rects = Array.from(element.getClientRects());
    if (rects.length === 0) rects = [element.getBoundingClientRect()];
    const leaves = rects.map((rect) => leafForRect(rect, hostRect, offset, leafSize, vertical, direction));
    const first = Math.min(...leaves);
    const last = Math.max(...leaves);
    return {
      chapterId: element.getAttribute('data-nekori-chapter-id') ?? '',
      startLeaf: first,
      leafCount: Math.max(1, last - first + 1),
      element,
    };
  });
}

export function rebuildBlankLeaves(root: HTMLElement, chapters: readonly MeasuredChapter[], double: boolean): void {
  root.querySelectorAll('.nekori-layout-blank-leaf').forEach((blank) => blank.remove());
  if (!double) return;
  for (const chapter of chapters) {
    if (chapter.leafCount % 2 === 0) continue;
    const blank = root.ownerDocument.createElement('div');
    blank.className = 'nekori-layout-blank-leaf';
    blank.setAttribute('aria-hidden', 'true');
    chapter.element.insertAdjacentElement('afterend', blank);
  }
}
