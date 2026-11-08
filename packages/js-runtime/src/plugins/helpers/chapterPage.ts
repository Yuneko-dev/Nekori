import type { ChapterItem, SourceNovel } from '../types';

const POSITIVE_INTEGER_PAGE = /^[1-9]\d*$/;

export const normalizePluginChapters = (
  pluginId: string,
  chapters: ChapterItem[],
  source: 'parseNovel' | 'parsePage',
  options?: {
    defaultPage?: '1' | 'Default';
    pageOverride?: string;
    validateNumeric?: boolean;
  },
): ChapterItem[] => {
  if (!Array.isArray(chapters)) {
    throw new Error(`[${pluginId}] ${source} returned an invalid chapter list`);
  }

  const {
    defaultPage = 'Default',
    pageOverride,
    validateNumeric = false,
  } = options ?? {};
  if (pageOverride !== undefined && !POSITIVE_INTEGER_PAGE.test(pageOverride)) {
    throw new Error(
      `[${pluginId}] ${source} was called with invalid page ${JSON.stringify(
        pageOverride,
      )}`,
    );
  }

  return chapters.map((chapter, index) => {
    const scanlator = Array.isArray(chapter.scanlator)
      ? chapter.scanlator
          .map(value => value.trim())
          .filter(Boolean)
          .join(', ') || undefined
      : chapter.scanlator?.trim() || undefined;
    try {
      const sourcePage = pageOverride ?? chapter.page;
      const page =
        sourcePage == null ||
        (!validateNumeric &&
          typeof sourcePage === 'string' &&
          !sourcePage.trim())
          ? defaultPage
          : sourcePage;
      if (typeof page !== 'string') {
        throw new Error('page must be a string');
      }
      if (validateNumeric && !POSITIVE_INTEGER_PAGE.test(page)) {
        throw new Error('page must be a positive integer string');
      }
      if (page.includes('\u200b')) {
        throw new Error('page must not contain a legacy volume marker');
      }
      return {
        ...chapter,
        page,
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

export const normalizePluginNovel = (
  pluginId: string,
  novel: SourceNovel,
  paged: boolean,
): SourceNovel => {
  const { totalPages } = novel;
  const validTotalPages = paged
    ? typeof totalPages === 'number' &&
      Number.isInteger(totalPages) &&
      totalPages >= 1
    : totalPages === undefined || totalPages === 0 || totalPages === 1;

  if (!validTotalPages) {
    throw new Error(
      `[${pluginId}] parseNovel returned invalid totalPages ${JSON.stringify(
        totalPages,
      )} for a ${paged ? 'paged' : 'non-paged'} plugin`,
    );
  }

  return {
    ...novel,
    totalPages: paged ? totalPages : 0,
    chapters: normalizePluginChapters(
      pluginId,
      novel.chapters,
      'parseNovel',
      paged ? { defaultPage: '1', validateNumeric: true } : undefined,
    ),
  };
};
