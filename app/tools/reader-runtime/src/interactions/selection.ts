import { CHAPTER_ID_ATTR, CHAPTER_TAG_NAME } from '../chapters/metadata.ts';
import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

const PARAGRAPH_SELECTOR = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,pre';

export function registerSelectionCommands(host: ReaderHost): Unsubscribe[] {
  return [
    host.onCommand('selection.read', () => host.root.ownerDocument.getSelection()?.toString() ?? null),
    host.onCommand('selection.readAnchor', () => readSelectionAnchor(host)),
    host.onCommand('selection.clear', () => {
      host.root.ownerDocument.getSelection()?.removeAllRanges();
    }),
    host.onCommand('selection.blur', () => {
      host.root.ownerDocument.getSelection()?.removeAllRanges();
      host.root.ownerDocument.activeElement instanceof HTMLElement && host.root.ownerDocument.activeElement.blur();
    }),
  ];
}

function readSelectionAnchor(host: ReaderHost): { paragraphIndex: number | null; text: string } | null {
  const selection = host.root.ownerDocument.getSelection();
  if (!selection || selection.isCollapsed || !selection.anchorNode) return null;
  const chapter = findChapter(host.root, host.chapter.id);
  if (!chapter) return { paragraphIndex: null, text: selection.toString() };
  const anchorElement = selection.anchorNode instanceof Element
    ? selection.anchorNode
    : selection.anchorNode.parentElement;
  const paragraph = anchorElement?.closest(PARAGRAPH_SELECTOR);
  const paragraphs = Array.from(chapter.querySelectorAll(PARAGRAPH_SELECTOR));
  const index = paragraph ? paragraphs.indexOf(paragraph) : -1;
  return { paragraphIndex: index < 0 ? null : index, text: selection.toString() };
}

export function findChapter(root: ParentNode, chapterId: string): HTMLElement | null {
  for (const chapter of root.querySelectorAll<HTMLElement>(CHAPTER_TAG_NAME)) {
    if (chapter.getAttribute(CHAPTER_ID_ATTR) === chapterId) return chapter;
  }
  return null;
}
