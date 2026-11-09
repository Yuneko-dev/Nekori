export const CHAPTER_TAG_NAME = 'nekori-chapter';
export const CHAPTER_ID_ATTR = 'data-nekori-chapter-id';
export const CHAPTER_TITLE_ATTR = 'data-nekori-chapter-name';
export const CHAPTER_NUMBER_ATTR = 'data-nekori-chapter-number';
export const CHAPTER_PATH_ATTR = 'data-nekori-chapter-path';
export const CHAPTER_URL_ATTR = 'data-nekori-chapter-url';
export const CHAPTER_DIVIDER_CLASS = 'nekori-chapter-divider';
export const CHAPTER_CLASS = 'nekori-chapter';
export const PLAIN_TEXT_CLASS = 'nekori-plain-text';
export const PLAIN_TEXT_ATTR = 'data-nekori-plain-text';

export interface ChapterMetadata {
  readonly id: string;
  readonly name: string;
  readonly number: number;
  /** Opaque source identity; preserve exactly as returned by the source. */
  readonly path: string;
  readonly url: string;
  readonly direction?: 'ltr' | 'rtl';
  readonly language?: string;
}

export interface ChapterNodes {
  readonly element: HTMLElement;
  readonly divider: HTMLDivElement;
}
