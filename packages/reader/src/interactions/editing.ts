import { CHAPTER_ID_ATTR, CHAPTER_TAG_NAME } from '../chapters/metadata.ts';
import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

const EDITABLE_ATTR = 'data-nekori-editable';

export function registerEditingCommands(host: ReaderHost): Unsubscribe[] {
  const document = host.root.ownerDocument;
  const snapshots = new Map<string, string>();
  const chapters = (): HTMLElement[] => Array.from(host.root.querySelectorAll<HTMLElement>(CHAPTER_TAG_NAME))
    .filter(chapter => host.config.chapters.some(item => item.id === chapter.getAttribute(CHAPTER_ID_ATTR)));
  let inputListener: (() => void) | null = null;
  const disable = (): void => {
    if (inputListener) document.removeEventListener('input', inputListener);
    inputListener = null;
    host.root.querySelectorAll<HTMLElement>(`[${EDITABLE_ATTR}="1"]`).forEach((element) => {
      element.removeAttribute('contenteditable');
      element.removeAttribute(EDITABLE_ATTR);
      element.removeAttribute('tabindex');
    });
    document.getElementById('nekori-edit-mode-style')?.remove();
  };
  const enter = (): boolean => {
    const targets = chapters();
    if (host.config.mode !== 'reader' || targets.length === 0) {
      disable();
      snapshots.clear();
      return false;
    }
    document.designMode = 'off';
    for (const chapter of targets) {
      const id = chapter.getAttribute(CHAPTER_ID_ATTR);
      if (id !== null && !snapshots.has(id)) snapshots.set(id, chapter.innerHTML);
      chapter.setAttribute('contenteditable', 'true');
      chapter.setAttribute(EDITABLE_ATTR, '1');
      chapter.setAttribute('tabindex', '0');
    }
    installEditStyles(document);
    if (!inputListener) {
      inputListener = () => host.emit('edit.changed', {});
      document.addEventListener('input', inputListener);
    }
    return true;
  };
  const saveAndExit = (): void => {
    const contents = chapters().map((chapter) => ({
      id: chapter.getAttribute(CHAPTER_ID_ATTR) ?? '',
      content: chapter.innerHTML,
    }));
    if (host.config.mode === 'reader' && snapshots.size > 0 && contents.length > 0) {
      host.emit('edit.save', { content: JSON.stringify(contents) });
    }
    disable();
    snapshots.clear();
  };
  const discard = (): void => {
    for (const [id, html] of snapshots) {
      const chapter = chapters()
        .find((item) => item.getAttribute(CHAPTER_ID_ATTR) === id);
      if (chapter) chapter.innerHTML = html;
    }
    disable();
    snapshots.clear();
  };
  const remove = [
    host.onCommand('edit.enter', enter),
    host.onCommand('edit.exit', saveAndExit),
    host.onCommand('edit.discard', discard),
  ];
  return [() => { disable(); snapshots.clear(); }, ...remove];
}

function installEditStyles(document: Document): void {
  if (document.getElementById('nekori-edit-mode-style')) return;
  const style = document.createElement('style');
  style.id = 'nekori-edit-mode-style';
  style.textContent = 'nekori-chapter, #nekori-reader, [data-nekori-editable="1"] { -webkit-user-select:text!important; user-select:text!important; pointer-events:auto!important; -webkit-tap-highlight-color:transparent; outline:none; } body { padding-bottom:max(220px,38vh)!important; }';
  document.head.append(style);
}
