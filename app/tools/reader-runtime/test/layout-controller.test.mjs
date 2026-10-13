import assert from 'node:assert/strict';
import test from 'node:test';
import { createLayout } from '../src/layout/index.ts';

class Observer {
  observe() {}
  disconnect() {}
}

class FakeElement {
  constructor(document) {
    this.ownerDocument = document;
    this.clientWidth = 100;
    this.clientHeight = 100;
    this.scrollWidth = 300;
    this.scrollHeight = 300;
    this.scrollLeft = 0;
    this.scrollTop = 0;
    this.dataset = {};
    this.classList = { add() {}, toggle() {} };
    this.style = {};
    this.listeners = new Map();
  }
  getBoundingClientRect() { return { left: 0, right: 100, top: 0, bottom: 100 }; }
  querySelectorAll() { return []; }
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  removeEventListener(name) { this.listeners.delete(name); }
  scrollTo(options) {
    if (typeof options === 'object' && 'left' in options) this.scrollLeft = options.left;
    if (typeof options === 'object' && 'top' in options) this.scrollTop = options.top;
  }
  removeAttribute() {}
  append() {}
}

function layoutFixture() {
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
    defaultView: view,
    fonts: undefined,
    addEventListener() {},
    removeEventListener() {},
    createElement() { return new FakeElement(document); },
  };
  const root = new FakeElement(document);
  const events = [];
  const layout = createLayout({ root, host: { emit: (name, payload) => events.push({ name, payload }) } });
  const flushFrames = () => {
    while (frames.size) {
      const [id, callback] = frames.entries().next().value;
      frames.delete(id);
      callback(0);
    }
  };
  return { root, layout, events, flushFrames };
}

test('paged layout restores a single-page blank-safe position and reports page ranges', () => {
  const { layout, events, flushFrames } = layoutFixture();
  layout.configure({ enabled: true, spread: 'double', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: 'chapter-1' });
  flushFrames();
  assert.deepEqual(layout.position, {
    chapterId: 'chapter-1', unitIndex: 0, unitCount: 3,
    firstPage: 1, lastPage: 2, totalPages: 6, progress: 0,
  });
  assert.equal(events.some((event) => event.name === 'layout.positionChanged'), true);
});

test('silent curl rollback restores the source page and does not persist; commit persists destination', () => {
  const { root, layout, events, flushFrames } = layoutFixture();
  layout.configure({ enabled: true, spread: 'single', direction: 'rtl', vertical: false, infinite: false, threshold: 0.8, chapterId: 'chapter-2' });
  flushFrames();
  events.length = 0;

  layout.prepareSilentTurn(false);
  assert.equal(layout.moveBy(1), true);
  layout.finishSilentTurn(false, false);
  assert.equal(Math.abs(root.scrollLeft), 0);
  assert.equal(events.some((event) => event.name === 'scroll.progressPersist'), false);

  layout.prepareSilentTurn(false);
  assert.equal(layout.moveBy(1), true);
  layout.finishSilentTurn(false, true);
  assert.equal(root.scrollLeft, -100);
  assert.equal(events.filter((event) => event.name === 'scroll.progressPersist').length, 1);
});

test('a pending reflow preserves an anchor reveal before the scroll publisher runs', () => {
  const { root, layout, flushFrames } = layoutFixture();
  layout.configure({ enabled: true, spread: 'single', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: 'chapter-3' });
  flushFrames();

  layout.reflow();
  assert.equal(layout.revealElement({ getBoundingClientRect: () => ({ left: 200, right: 250, top: 0, bottom: 20 }) }), true);
  assert.equal(root.scrollLeft, 200);
  flushFrames();

  assert.equal(root.scrollLeft, 200);
});
