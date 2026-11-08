import { CHAPTER_TAG_NAME } from '../chapters/metadata.ts';
import type { LayoutController } from '../layout/types.ts';

export function findChapterAnchor(chapter: Element, name: string): Element | null {
  for (const element of chapter.querySelectorAll<HTMLElement>('[id],a[name]')) {
    if (element.id === name || element.getAttribute('name') === name) return element;
  }
  return null;
}

/** Keeps fragment resolution inside its chapter, avoiding duplicate IDs in loaded chapters. */
export function installScopedAnchors(
  root: HTMLElement,
  getLayout: () => LayoutController | null,
): () => void {
  const onClick = (rawEvent: Event): void => {
    const event = rawEvent as MouseEvent;
    if (
      event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey ||
      event.shiftKey || event.altKey
    ) return;

    const target = event.target;
    const targetElement = target instanceof Element ? target : target instanceof Node ? target.parentElement : null;
    const link = targetElement?.closest<HTMLAnchorElement>('a[href^="#"]');
    if (!link || !root.contains(link)) return;

    const layout = getLayout();
    const paged = layout?.enabled ?? false;
    const chapter = link.closest(CHAPTER_TAG_NAME) ?? (paged ? root : null);
    // Keep browser-native fragment behavior in ordinary scrolling mode for unwrapped source HTML.
    if (!chapter) return;

    let fragment = link.getAttribute('href')?.slice(1) ?? '';
    if (!fragment && !paged) return;
    try {
      fragment = decodeURIComponent(fragment);
    } catch {
      // A malformed encoded fragment still gets a chance to match its literal spelling.
    }
    event.preventDefault();
    const destination = fragment ? findChapterAnchor(chapter, fragment) : chapter;
    if (!destination) return;
    if (paged && layout) layout.revealElement(destination);
    else destination.scrollIntoView({ behavior: 'auto', block: 'start' });
  };

  root.addEventListener('click', onClick, true);
  return () => root.removeEventListener('click', onClick, true);
}
