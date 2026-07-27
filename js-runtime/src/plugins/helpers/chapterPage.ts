import type { ChapterItem } from '../types';

export const VOLUME_PAGE_MARKER = '\u200b' as const;

export type VolumePage = `${string}${typeof VOLUME_PAGE_MARKER}`;

const POSITIVE_INTEGER_PAGE = /^[1-9]\d*$/;

export const createVolumePage = (name: string): VolumePage => {
  const normalizedName = name.replace(/\u200b/g, '');
  if (!normalizedName.trim()) {
    throw new Error('Volume name must be non-empty');
  }

  return `${normalizedName}${VOLUME_PAGE_MARKER}`;
};

export const normalizeChapterPage = (page: unknown): string => {
  if (page === undefined) {
    return '1';
  }

  if (typeof page !== 'string') {
    throw new Error('page must be a string');
  }

  if (POSITIVE_INTEGER_PAGE.test(page)) {
    return page;
  }

  if (page.endsWith(VOLUME_PAGE_MARKER)) {
    const name = page.slice(0, -VOLUME_PAGE_MARKER.length);
    if (name.trim() && !name.includes(VOLUME_PAGE_MARKER)) {
      return page;
    }
  }

  throw new Error(
    'page must be a positive integer string or a volume name ending with \\u200b',
  );
};

export const normalizePluginChapters = (
  pluginId: string,
  chapters: ChapterItem[],
  source: 'parseNovel' | 'parsePage',
): ChapterItem[] => {
  if (!Array.isArray(chapters)) {
    throw new Error(`[${pluginId}] ${source} returned an invalid chapter list`);
  }

  return chapters.map((chapter, index) => {
    const scanlator = Array.isArray(chapter.scanlator)
      ? chapter.scanlator.join(', ')
      : chapter.scanlator;
    try {
      return {
        ...chapter,
        page: normalizeChapterPage(chapter.page),
        scanlator,
      };
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const chapterIdentity = chapter?.path || chapter?.name || `#${index + 1}`;
      throw new Error(
        `[${pluginId}] ${source} returned invalid page ${JSON.stringify(
          chapter?.page,
        )} for chapter ${JSON.stringify(chapterIdentity)}: ${reason}`,
      );
    }
  });
};
