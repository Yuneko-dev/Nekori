const WORD = /(\p{L}|\p{Nd})*\p{L}(\p{L}|\p{Nd})*/gu;
const THRESHOLDS = [0, 4, 12, 17, 24, 29, 35, 42, 48];
const MARKER = 'data-nekori-bionic';
const EXCLUDED = [
  'script', 'style', 'code', 'pre', 'textarea', 'button', '[contenteditable="true"]',
  '#nekori-image-modal', '#nekori-tts-controller', '#nekori-next-chapter',
  'nekori-chapter-summary', `b[${MARKER}]`,
].join(',');

export function fixationLength(word: string): number {
  const thresholdIndex = THRESHOLDS.findIndex((threshold) => word.length <= threshold);
  return Math.max(thresholdIndex === -1 ? word.length - THRESHOLDS.length : word.length - thresholdIndex, 0);
}

export interface BionicReading {
  setEnabled(enabled: boolean): void;
  setEditing(editing: boolean): void;
  dispose(): void;
}

export function createBionicReading(root: HTMLElement, initiallyEnabled: boolean, editing: () => boolean): BionicReading {
  const document = root.ownerDocument;
  const window = document.defaultView;
  if (!window) throw new Error('Bionic reading requires an active document window');
  let enabled = false;
  let observer: MutationObserver | null = null;

  const apply = (node: Node): void => {
    if (!enabled || editing() || !node.isConnected) return;
    if (node.nodeType === window.Node.TEXT_NODE) {
      const textNode = node as Text;
      const text = textNode.nodeValue ?? '';
      if (!text.trim() || textNode.parentElement?.closest(EXCLUDED)) return;
      transformText(textNode, text, document);
      return;
    }
    if (node.nodeType !== window.Node.ELEMENT_NODE) return;
    const element = node as Element;
    if (element.matches(EXCLUDED)) return;
    const walker = document.createTreeWalker(element, window.NodeFilter.SHOW_TEXT);
    const nodes: Text[] = [];
    while (walker.nextNode()) {
      const textNode = walker.currentNode as Text;
      if (textNode.nodeValue?.trim() && !textNode.parentElement?.closest(EXCLUDED)) nodes.push(textNode);
    }
    nodes.forEach((textNode) => transformText(textNode, textNode.nodeValue ?? '', document));
  };

  const remove = (): void => {
    root.querySelectorAll<HTMLElement>(`b[${MARKER}]`).forEach((strong) => {
      strong.replaceWith(document.createTextNode(strong.textContent ?? ''));
    });
    root.normalize();
  };

  const observe = (): void => {
    if (observer) observer.observe(root, { childList: true, subtree: true });
  };

  const setEnabled = (value: boolean): void => {
    enabled = value && !editing();
    observer?.disconnect();
    if (!enabled) {
      remove();
      return;
    }
    apply(root);
    if (!observer) {
      observer = new window.MutationObserver((mutations) => {
        observer?.disconnect();
        for (const mutation of mutations) for (const node of mutation.addedNodes) apply(node);
        observe();
      });
    }
    observe();
  };

  const setEditing = (value: boolean): void => {
    if (value && enabled) {
      enabled = false;
      observer?.disconnect();
      remove();
    }
  };

  setEnabled(initiallyEnabled);
  return {
    setEnabled,
    setEditing,
    dispose() { observer?.disconnect(); observer = null; remove(); },
  };
}

function transformText(node: Text, text: string, document: Document): void {
  WORD.lastIndex = 0;
  if (!WORD.test(text)) return;
  WORD.lastIndex = 0;
  const fragment = document.createDocumentFragment();
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = WORD.exec(text)) !== null) {
    if (match.index > last) fragment.append(document.createTextNode(text.slice(last, match.index)));
    const word = match[0];
    const length = fixationLength(word);
    if (length > 0) {
      const strong = document.createElement('b');
      strong.setAttribute(MARKER, '');
      strong.textContent = word.slice(0, length);
      fragment.append(strong);
    }
    fragment.append(document.createTextNode(word.slice(length)));
    last = match.index + word.length;
  }
  if (last < text.length) fragment.append(document.createTextNode(text.slice(last)));
  node.replaceWith(fragment);
}
