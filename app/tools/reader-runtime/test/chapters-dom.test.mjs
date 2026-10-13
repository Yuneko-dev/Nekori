import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createChapterElements,
  prependChapter,
  replaceChapterText,
  applyProcessedContent,
  CHAPTER_TAG_NAME,
  CHAPTER_ID_ATTR,
  CHAPTER_PATH_ATTR,
} from '../src/chapters/index.ts';

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.attributes = new Map();
    this.children = [];
    this.parentNode = null;
    this.textContent = '';
    this.scrollTop = 0;
    this.scrollLeft = 0;
    this.clientHeight = 100;
    this.clientWidth = 100;
    this.rectTop = 0;
    this.rectLeft = 0;
    this.height = 0;
    this.classSet = new Set();
    this.classList = {
      add: (value) => this.classSet.add(value),
      contains: (value) => this.classSet.has(value),
    };
    this.style = { cssText: '', whiteSpace: '', wordBreak: '', overflowWrap: '' };
  }
  get firstElementChild() { return this.children.find((node) => node.nodeType !== 3) ?? null; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  append(...nodes) {
    for (const node of nodes) {
      const inserted = node.tagName === '#fragment' ? node.children : [node];
      for (const child of inserted) { child.parentNode = this; this.children.push(child); }
      if (node.tagName === '#fragment') node.children = [];
    }
  }
  insertBefore(node, next) {
    node.parentNode = this;
    const index = this.children.indexOf(next);
    const position = index < 0 ? this.children.length : index;
    this.children.splice(position, 0, node);
    for (const sibling of this.children.slice(position + 1)) sibling.rectTop += node.height;
  }
  getBoundingClientRect() { return { top: this.rectTop, left: this.rectLeft }; }
  querySelectorAll() { return []; }
  replaceChildren(...nodes) {
    this.children = nodes.flatMap((node) => node.tagName === '#fragment' ? node.children : [node]);
    for (const node of this.children) node.parentNode = this;
  }
  remove() {
    if (!this.parentNode) return;
    const index = this.parentNode.children.indexOf(this);
    if (index >= 0) this.parentNode.children.splice(index, 1);
    this.parentNode = null;
  }
}

const fakeDocument = {
  createElement: (name) => new FakeElement(name),
  createTextNode: (text) => ({ nodeType: 3, textContent: text, parentNode: null }),
  createDocumentFragment: () => new FakeElement('#fragment'),
};

test('chapter metadata keeps new Nekori hooks and source path verbatim', () => {
  const chapter = createChapterElements(fakeDocument, {
    id: '9007199254740993', name: 'A title', number: 2.5,
    path: '//opaque/path/#fragment', url: 'https://source.test/chapter',
  });
  assert.equal(chapter.element.tagName, CHAPTER_TAG_NAME);
  assert.equal(chapter.element.classList.contains('nekori-chapter'), true);
  assert.equal(chapter.element.getAttribute(CHAPTER_ID_ATTR), '9007199254740993');
  assert.equal(chapter.element.getAttribute(CHAPTER_PATH_ATTR), '//opaque/path/#fragment');
  assert.equal(chapter.divider.classList.contains('nekori-chapter-divider'), true);
});

test('prepending preserves the first visible element position', () => {
  const root = new FakeElement('div');
  const visible = new FakeElement('p');
  visible.rectTop = 20;
  root.append(visible);
  const prependNode = new FakeElement('nekori-chapter');
  prependNode.height = 80;
  root.scrollTop = 130;
  prependChapter(root, { element: prependNode, divider: new FakeElement('div') }, { scrollContainer: root, anchor: visible });
  assert.equal(root.children[1], prependNode);
  assert.equal(root.scrollTop, 210);
});

test('plain text replacement creates text nodes rather than parsing markup', () => {
  const chapter = new FakeElement('nekori-chapter');
  replaceChapterText(fakeDocument, chapter, '<script>alert(1)</script>\ncontinued\n\nnext');
  const paragraphs = chapter.children[0].children;
  assert.deepEqual(paragraphs.map((paragraph) => paragraph.children[0].textContent), ['<script>alert(1)</script>\ncontinued', 'next']);
  assert.deepEqual(paragraphs.map((paragraph) => paragraph.children[0].nodeType), [3, 3]);
});

test('processed content routes plain text to paragraphs and HTML to the markup path', () => {
  const chapter = new FakeElement('nekori-chapter');
  applyProcessedContent(fakeDocument, chapter, { kind: 'plainText', text: '<b>literal</b>\n\nsecond' });
  assert.equal(chapter.children[0].children[0].children[0].textContent, '<b>literal</b>');

  applyProcessedContent(fakeDocument, chapter, { kind: 'html', html: '<p><b>rich</b></p>' });
  assert.equal(chapter.innerHTML, '<p><b>rich</b></p>');
});
