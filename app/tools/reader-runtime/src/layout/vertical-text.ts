export function combineShortNumbers(root: HTMLElement): void {
  const document = root.ownerDocument;
  const showText = document.defaultView?.NodeFilter.SHOW_TEXT;
  if (showText === undefined) return;
  const walker = document.createTreeWalker(root, showText);
  const nodes: Text[] = [];
  while (walker.nextNode()) {
    const node = walker.currentNode as Text;
    const parent = node.parentElement;
    if (!parent || !/[0-9]{2}/.test(node.data)) continue;
    if (parent.closest('.tcy,script,style,textarea,code,pre,rt,rp,svg,math,[contenteditable]')) continue;
    if (document.defaultView?.getComputedStyle(parent).textCombineUpright === 'all') continue;
    nodes.push(node);
  }

  for (const node of nodes) {
    const pattern = /(^|[^0-9A-Za-z])([0-9]{2})(?![0-9A-Za-z])/g;
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    let matched = false;
    for (const match of node.data.matchAll(pattern)) {
      const index = match.index;
      const prefix = match[1] ?? '';
      const digits = match[2] ?? '';
      const start = index + prefix.length;
      fragment.append(document.createTextNode(node.data.slice(cursor, start)));
      const span = document.createElement('span');
      span.className = 'tcy';
      span.textContent = digits;
      fragment.append(span);
      cursor = start + digits.length;
      matched = true;
    }
    if (!matched) continue;
    fragment.append(document.createTextNode(node.data.slice(cursor)));
    node.replaceWith(fragment);
  }
}
