import assert from 'node:assert/strict';
import test from 'node:test';
import { createScrollTracking } from '../src/layout/index.ts';

class Observer {
  constructor(callback) { this.callback = callback; }
  observe() {}
  disconnect() {}
}

function trackingFixture() {
  let nextFrame = 1;
  const frames = new Map();
  const listeners = new Map();
  const view = {
    innerHeight: 100,
    scrollY: 480,
    pageYOffset: 480,
    ResizeObserver: Observer,
    MutationObserver: Observer,
    Node: { DOCUMENT_POSITION_FOLLOWING: 4 },
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name) { listeners.delete(name); },
    dispatchEvent() {},
    requestAnimationFrame(callback) { const id = nextFrame++; frames.set(id, callback); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    setTimeout() { return 1; },
    clearTimeout() {},
  };
  const documentElement = { scrollHeight: 580, clientHeight: 100, scrollTop: 0 };
  const body = { scrollHeight: 580, scrollTop: 0 };
  const document = {
    defaultView: view,
    documentElement,
    body,
    scrollingElement: documentElement,
    getElementById() { return null; },
  };
  const divider = (id, top) => ({
    getAttribute: () => id,
    getBoundingClientRect: () => ({ top: top - view.scrollY }),
  });
  const root = {
    ownerDocument: document,
    dataset: {},
    querySelectorAll: () => [divider('first', 0), divider('short-last', 500)],
    addEventListener() {},
  };
  const emitted = [];
  const controller = createScrollTracking({ root, host: { emit: (name, payload) => emitted.push({ name, payload }) } });
  const flushFrames = () => {
    while (frames.size) {
      const [id, callback] = frames.entries().next().value;
      frames.delete(id);
      callback(0);
    }
  };
  return { controller, emitted, flushFrames };
}

test('clearing the load latch re-samples an unchanged short final chapter', () => {
  const { controller, emitted, flushFrames } = trackingFixture();
  controller.configure({ infiniteScrollEnabled: true, loadThreshold: 0.8, doneThreshold: 0.95 });
  flushFrames();
  assert.equal(emitted.filter(({ name }) => name === 'navigation.loadNext').length, 1);
  controller.setLoadingNext(false);
  flushFrames();
  assert.equal(emitted.filter(({ name }) => name === 'navigation.loadNext').length, 2);
  assert.equal(emitted.some(({ name, payload }) => name === 'chapter.visible' && payload.chapterId === 'short-last'), true);
  controller.dispose();
});
