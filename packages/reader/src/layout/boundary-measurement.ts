import { boundariesFromOffsets, type ChapterBoundary } from './progress.ts';
import { CHAPTER_DIVIDER_CLASS, CHAPTER_ID_ATTR } from '../chapters/metadata.ts';

export function measureChapterBoundaries(root: HTMLElement): ChapterBoundary[] {
  const document = root.ownerDocument;
  const view = document.defaultView;
  if (!view) return [];
  const dividers = Array.from(root.querySelectorAll<HTMLElement>(`.${CHAPTER_DIVIDER_CLASS}`));
  const scrollY = view.scrollY || view.pageYOffset || 0;
  const banner = document.getElementById('inline-loading') ?? document.getElementById('inline-error');
  const lastDivider = dividers.at(-1);
  const bannerTrails = !!banner && (!lastDivider || Boolean(
    lastDivider.compareDocumentPosition(banner) & view.Node.DOCUMENT_POSITION_FOLLOWING,
  ));
  const documentEnd = bannerTrails
    ? banner!.getBoundingClientRect().top + scrollY
    : document.body?.scrollHeight ?? Math.max(document.documentElement.scrollHeight, 0);
  const ids = dividers.map((divider) => divider.getAttribute(CHAPTER_ID_ATTR) ?? '');
  const offsets = dividers.map((divider) => divider.getBoundingClientRect().top + scrollY);
  return boundariesFromOffsets(ids, offsets, documentEnd);
}
