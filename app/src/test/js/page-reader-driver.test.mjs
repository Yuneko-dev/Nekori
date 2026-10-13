import assert from 'node:assert/strict';
import test from 'node:test';
import { createLayout } from '../../../tools/reader-runtime/src/layout/index.ts';

class Observer {
  observe() {}
  disconnect() {}
}

class FakeElement {
  constructor(document, { clientWidth = 100, clientHeight = 100, scrollWidth = 300, scrollHeight = 300 } = {}) {
    this.ownerDocument = document;
    this.clientWidth = clientWidth;
    this.clientHeight = clientHeight;
    this.scrollWidth = scrollWidth;
    this.scrollHeight = scrollHeight;
    this.scrollLeft = 0;
    this.scrollTop = 0;
    this.dataset = {};
    this.style = {};
    this.attributes = new Map();
    this.classes = new Set();
    this.listeners = new Map();
    this.classList = {
      add: (name) => this.classes.add(name),
      toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name),
    };
  }
  getBoundingClientRect() { return { left: 0, right: this.clientWidth, top: 0, bottom: this.clientHeight }; }
  getClientRects() { return []; }
  querySelectorAll() { return []; }
  addEventListener(name, handler) { this.listeners.set(name, handler); }
  removeEventListener(name) { this.listeners.delete(name); }
  dispatchEvent(event) { this.listeners.get(event.type)?.(event); }
  removeAttribute(name) { this.attributes.delete(name); if (name === 'dir') delete this.dir; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  append() {}
  scrollTo(options) {
    if ('left' in options) this.scrollLeft = options.left;
    if ('top' in options) this.scrollTop = options.top;
  }
}

function createHarness({ spread = 'single', direction = 'ltr', scrollWidth = 300 } = {}) {
  const frames = new Map();
  let nextFrame = 1;
  const view = {
    ResizeObserver: Observer,
    MutationObserver: Observer,
    HTMLImageElement: class HTMLImageElement {},
    requestAnimationFrame(callback) { const id = nextFrame++; frames.set(id, callback); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    setTimeout() { return 1; },
    clearTimeout() {},
  };
  const document = {
    defaultView: view, fonts: undefined,
    addEventListener() {}, removeEventListener() {},
    createElement() { return new FakeElement(document); },
  };
  const root = new FakeElement(document, { scrollWidth });
  const messages = [];
  const layout = createLayout({ root, host: { emit: (method, payload) => messages.push({ method, payload }) } });
  const configure = (enabled = true, infinite = false) => layout.configure({
    enabled, spread, direction, vertical: false, infinite, threshold: 0.8, chapterId: '7',
  });
  const flushFrames = () => {
    while (frames.size) {
      const [id, callback] = frames.entries().next().value;
      frames.delete(id);
      callback(0);
    }
  };
  configure();
  flushFrames();
  return { layout, root, messages, flushFrames, configure };
}

test('reports chapter-local position through the production layout controller', () => {
  const { layout, messages } = createHarness();
  assert.deepEqual(layout.position, {
    chapterId: '7', unitIndex: 0, unitCount: 3, firstPage: 1, lastPage: 1, totalPages: 3, progress: 0,
  });
  assert.ok(messages.some(({ method }) => method === 'layout.positionChanged'));
});

test('clamps paged scrolling to the first and last complete visual units', () => {
  const { layout, root } = createHarness();
  root.scrollLeft = 999;
  root.dispatchEvent(new Event('scroll'));
  assert.equal(root.scrollLeft, 200);
  root.scrollLeft = -50;
  root.dispatchEvent(new Event('scroll'));
  assert.equal(root.scrollLeft, 0);
});

test('does not load a previous chapter from the first visual unit and requests next at the last', () => {
  const { layout, messages } = createHarness();
  assert.equal(layout.moveBy(-1, 'instant'), false);
  assert.equal(layout.seekUnit(2), true);
  assert.equal(layout.moveBy(1, 'instant'), false);
  assert.equal(messages.filter(({ method }) => method === 'navigation.loadNext').length, 1);
});

test('curl commit persists one target position, rollback emits no progress, and preview stays on one unit', () => {
  const { layout, root, messages } = createHarness();
  layout.prepareSilentTurn(false);
  assert.equal(layout.moveBy(1, 'instant'), true);
  layout.finishSilentTurn(false, true);
  assert.equal(root.scrollLeft, 100);
  assert.equal(messages.filter(({ method }) => method === 'scroll.progressPersist').length, 1);

  messages.length = 0;
  layout.seekUnit(0);
  layout.prepareSilentTurn(false);
  layout.moveBy(1, 'instant');
  layout.finishSilentTurn(false, false);
  assert.equal(root.scrollLeft, 0);
  assert.equal(messages.some(({ method }) => method === 'scroll.progressPersist'), false);

  layout.prepareSilentTurn(false);
  layout.moveBy(1, 'instant');
  layout.moveBy(1, 'instant');
  assert.equal(root.scrollLeft, 100);
});

test('boundary turn can request an incompatible next chapter after the no-more latch', () => {
  const { layout, root, messages, configure } = createHarness();
  configure(true, true);
  root.dataset.nekoriNoMoreChapters = 'true';
  layout.seekUnit(2);
  layout.moveBy(1, 'instant');
  assert.equal(messages.filter(({ method }) => method === 'navigation.loadNext').length, 1);
});

test('book direction is removed when pagination is disabled', () => {
  const { layout, root, configure } = createHarness({ direction: 'rtl' });
  assert.equal(root.dir, 'rtl');
  configure(false);
  assert.equal(root.dir, undefined);
  assert.equal(root.classes.has('is-paged'), false);
});

test('moves one visual unit in LTR and mirrored RTL double-page layouts', () => {
  for (const direction of ['ltr', 'rtl']) {
    const { layout, root } = createHarness({ spread: 'double', direction, scrollWidth: 400 });
    assert.equal(layout.moveBy(1, 'instant'), true);
    assert.equal(root.scrollLeft, direction === 'rtl' ? -100 : 100);
    assert.equal(layout.position?.lastPage, 2);
  }
});

test('the shared layout controller works without a native bridge', () => {
  const { layout, root } = createHarness();
  layout.moveBy(1, 'instant');
  assert.equal(root.scrollLeft, 100);
  assert.equal(layout.position?.unitIndex, 0);
});

test('reflow restores 50 and 63 percent to pages five and six of nine', () => {
  for (const [percent, unit] of [[50, 4], [63, 5]]) {
    const { layout, root, flushFrames } = createHarness({ scrollWidth: 900 });
    layout.seekPercent(percent);
    assert.equal(root.scrollLeft, unit * 100);
    layout.reflow();
    flushFrames();
    assert.equal(root.scrollLeft, unit * 100);
    assert.equal(layout.position?.unitIndex, unit);
  }
});
