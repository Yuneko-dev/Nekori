import {
  CHAPTER_CLASS,
  CHAPTER_DIVIDER_CLASS,
  CHAPTER_ID_ATTR,
  CHAPTER_NUMBER_ATTR,
  CHAPTER_PATH_ATTR,
  CHAPTER_TAG_NAME,
  CHAPTER_TITLE_ATTR,
  CHAPTER_URL_ATTR,
  PLAIN_TEXT_ATTR,
  PLAIN_TEXT_CLASS,
  type ChapterMetadata,
  type ChapterNodes,
} from './metadata.ts';

function setMetadata(element: Element, chapter: ChapterMetadata): void {
  element.setAttribute(CHAPTER_ID_ATTR, chapter.id);
  element.setAttribute(CHAPTER_TITLE_ATTR, chapter.name);
  element.setAttribute(CHAPTER_NUMBER_ATTR, String(chapter.number));
  element.setAttribute(CHAPTER_PATH_ATTR, chapter.path);
  element.setAttribute(CHAPTER_URL_ATTR, chapter.url);
}

export function createChapterElements(document: Document, chapter: ChapterMetadata): ChapterNodes {
  const element = document.createElement(CHAPTER_TAG_NAME);
  element.classList.add(CHAPTER_CLASS);
  element.setAttribute('dir', chapter.direction ?? 'ltr');
  if (chapter.language !== undefined) element.setAttribute('lang', chapter.language);
  setMetadata(element, chapter);

  const divider = document.createElement('div');
  divider.classList.add(CHAPTER_DIVIDER_CLASS);
  divider.setAttribute('aria-hidden', 'true');
  divider.style.cssText = 'visibility:hidden;height:0;margin:0;padding:0;border:none;';
  setMetadata(divider, chapter);
  return { element, divider };
}

export interface PrependOptions {
  readonly scrollContainer?: HTMLElement;
  readonly anchor?: Element | null;
}

export function appendChapter(root: HTMLElement, nodes: ChapterNodes): void {
  root.append(nodes.divider, nodes.element);
}

/** Inserts before existing chapters and compensates for document scroll anchoring. */
export function prependChapter(root: HTMLElement, nodes: ChapterNodes, options: PrependOptions = {}): void {
  const scrollContainer = options.scrollContainer ?? root.ownerDocument.defaultView?.document.scrollingElement;
  const anchor = options.anchor ?? root.firstElementChild;
  const before = anchor?.getBoundingClientRect();
  const scrollTop = scrollContainer?.scrollTop ?? 0;
  const scrollLeft = scrollContainer?.scrollLeft ?? 0;
  root.insertBefore(nodes.element, root.firstElementChild);
  root.insertBefore(nodes.divider, nodes.element);
  if (!scrollContainer || !anchor || !before) return;

  const after = anchor.getBoundingClientRect();
  scrollContainer.scrollTop = scrollTop + after.top - before.top;
  scrollContainer.scrollLeft = scrollLeft + after.left - before.left;
}

export function removeChapter(root: HTMLElement, chapterId: string): boolean {
  const nodes = Array.from(root.querySelectorAll(`${CHAPTER_TAG_NAME}, .${CHAPTER_DIVIDER_CLASS}`))
    .filter((node) => node.getAttribute(CHAPTER_ID_ATTR) === chapterId);
  if (nodes.length === 0) return false;
  nodes.forEach((node) => node.remove());
  return true;
}

/** Plain text is inserted only through Text nodes, so source text cannot become markup. */
export function replaceChapterText(document: Document, chapter: HTMLElement, text: string): void {
  const fragment = document.createDocumentFragment();
  const paragraphs = text.split(/\n{2,}/).filter((paragraph) => paragraph.length > 0);
  const container = document.createElement('div');
  container.className = PLAIN_TEXT_CLASS;
  container.setAttribute(PLAIN_TEXT_ATTR, '1');
  for (const paragraph of paragraphs) {
    const element = document.createElement('p');
    element.style.whiteSpace = 'pre-wrap';
    element.style.wordBreak = 'break-word';
    element.style.overflowWrap = 'anywhere';
    element.append(document.createTextNode(paragraph));
    fragment.append(element);
  }
  container.append(fragment);
  chapter.replaceChildren(container);
}

/** Preserve HTML chapter semantics, including the current WebView setHTMLUnsafe path. */
export function setChapterHtml(chapter: HTMLElement, html: string): void {
  const unsafeElement = chapter as HTMLElement & { setHTMLUnsafe?: (value: string) => void };
  if (typeof unsafeElement.setHTMLUnsafe === 'function') unsafeElement.setHTMLUnsafe(html);
  else chapter.innerHTML = html;
}

export type ProcessedContent =
  | { readonly kind: 'plainText'; readonly text: string }
  | { readonly kind: 'html'; readonly html: string };

/** Applies runtime content with plain text kept inert and HTML kept in the HTML path. */
export function applyProcessedContent(
  document: Document,
  chapter: HTMLElement,
  content: ProcessedContent,
): void {
  if (content.kind === 'plainText') replaceChapterText(document, chapter, content.text);
  else setChapterHtml(chapter, content.html);
}
