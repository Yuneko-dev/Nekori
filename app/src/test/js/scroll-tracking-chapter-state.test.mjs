import assert from 'node:assert/strict';
import test from 'node:test';
import { measureChapterBoundaries } from '../../../tools/reader-runtime/src/layout/boundary-measurement.ts';
import { createScrollTracking } from '../../../tools/reader-runtime/src/layout/scroll-tracking.ts';

function trackingFixture() {
  let nextFrame = 1;
  const frames = new Map();
  const listeners = new Map();
  const emitted = [];
  let scrollY = 0;
  let dividers = [divider('1', 0), divider('2', 1_000)];
  let banner = null;
  let chapterOffset = 0;
  const body = { scrollHeight: 4_000, scrollTop: 0, children: [] };
  const view = {
    innerHeight: 800, Node: { DOCUMENT_POSITION_FOLLOWING: 4 },
    ResizeObserver: class { observe() {} disconnect() {} },
    MutationObserver: class { observe() {} disconnect() {} },
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    get scrollY() { return scrollY; }, get pageYOffset() { return scrollY; },
    addEventListener(type, listener) { const group = listeners.get(type) ?? new Set(); group.add(listener); listeners.set(type, group); },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener); },
    dispatchEvent(event) { for (const listener of listeners.get(event.type) ?? []) listener(event); },
    requestAnimationFrame(callback) { const id = nextFrame++; frames.set(id, callback); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    setTimeout(callback) { return setTimeout(callback, 0); },
    clearTimeout(id) { clearTimeout(id); },
  };
  const documentElement = { clientHeight: 800, scrollHeight: 4_000, scrollTop: 0 };
  const document = {
    defaultView: view, body, documentElement, scrollingElement: documentElement,
    getElementById(id) { return banner?.id === id ? banner : null; },
  };
  const root = {
    ownerDocument: document,
    querySelectorAll() { return dividers; },
  };
  const controller = createScrollTracking({ root, host: { emit: (name, payload) => emitted.push({ name, payload }) } });
  const flushFrames = () => {
    while (frames.size) {
      const [id, callback] = frames.entries().next().value;
      frames.delete(id);
      callback(0);
    }
  };
  const setScroll = (value) => { scrollY = value; documentElement.scrollTop = value; view.dispatchEvent(new Event('scroll')); };
  function divider(chapterId, absoluteTop) {
    return {
      getAttribute: (name) => name === 'data-nekori-chapter-id' ? chapterId : null,
      getBoundingClientRect: () => ({ top: absoluteTop + chapterOffset - scrollY }),
      compareDocumentPosition(other) { return body.children.indexOf(this) < body.children.indexOf(other) ? 4 : 2; },
    };
  }
  function setBanner(id, placement) {
    chapterOffset = placement === 'leading' ? 100 : 0;
    body.scrollHeight = placement === 'none' ? 4_000 : 4_100;
    banner = placement === 'none' ? null : {
      id, getBoundingClientRect: () => ({ top: (placement === 'leading' ? 0 : 4_000) - scrollY }),
    };
    dividers = [divider('1', 0), divider('2', 1_000)];
    body.children = placement === 'leading' ? [banner, ...dividers] : [...dividers, ...(banner ? [banner] : [])];
    documentElement.scrollHeight = body.scrollHeight;
  }
  body.children = [...dividers];
  return {
    controller, emitted, flushFrames, setScroll,
    configure() { controller.configure({ infiniteScrollEnabled: true, loadThreshold: 0.8, doneThreshold: 0.99 }); flushFrames(); },
    replaceSecondDivider(id) { dividers = [divider('1', 0), divider(id, 1_000)]; body.children = [...dividers]; },
    setBanner, root, view,
    dispose() { controller.dispose(); },
  };
}

test('scroll-end persistence publishes chapter-start zero through the shared progress calculation', () => {
  const fixture = trackingFixture();
  fixture.configure();
  fixture.setScroll(20);
  fixture.flushFrames();
  fixture.setScroll(0);
  fixture.flushFrames();
  fixture.view.dispatchEvent(new Event('scrollend'));
  assert.equal(fixture.emitted.filter((event) => event.name === 'scroll.progressPersist').at(-1).payload.progress, 0);
  fixture.dispose();
});

test('chapter visibility uses stable ids even when the boundary index stays unchanged', () => {
  const fixture = trackingFixture();
  fixture.configure();
  fixture.setScroll(1_000);
  fixture.flushFrames();
  fixture.replaceSecondDivider('3');
  fixture.controller.updateChapterBoundaries();
  fixture.flushFrames();
  const visible = fixture.emitted.filter((event) => event.name === 'chapter.visible').map((event) => event.payload.chapterId);
  assert.deepEqual(visible, ['1', '2', '3']);
  fixture.dispose();
});

test('resetChapterTracking re-samples a stationary viewport', () => {
  const fixture = trackingFixture();
  fixture.configure();
  fixture.setScroll(1_000);
  fixture.flushFrames();
  fixture.controller.resetChapterTracking();
  fixture.flushFrames();
  const visible = fixture.emitted.filter((event) => event.name === 'chapter.visible').map((event) => event.payload.chapterId);
  assert.deepEqual(visible, ['1', '2', '2']);
  fixture.dispose();
});

test('measured boundaries exclude trailing status banners and preserve prepended content offsets', () => {
  const fixture = trackingFixture();
  fixture.configure();
  for (const id of ['inline-loading', 'inline-error']) {
    fixture.setBanner(id, 'trailing');
    assert.deepEqual(measureChapterBoundaries(fixture.root), [
      { chapterId: '1', startOffset: 0, height: 1_000 },
      { chapterId: '2', startOffset: 1_000, height: 3_000 },
    ]);
  }
  fixture.setBanner('inline-loading', 'leading');
  assert.deepEqual(measureChapterBoundaries(fixture.root), [
    { chapterId: '1', startOffset: 100, height: 1_000 },
    { chapterId: '2', startOffset: 1_100, height: 3_000 },
  ]);
  fixture.dispose();
});

test('last-chapter progress stays stable while a trailing banner appears and disappears', () => {
  const fixture = trackingFixture();
  fixture.configure();
  fixture.setScroll(2_600);
  fixture.flushFrames();
  const before = fixture.emitted.filter((event) => event.name === 'scroll.progressPreview').at(-1).payload.progress;
  fixture.setBanner('inline-loading', 'trailing');
  fixture.controller.updateChapterBoundaries(); fixture.flushFrames();
  const during = fixture.emitted.filter((event) => event.name === 'scroll.progressPreview').at(-1).payload.progress;
  fixture.setBanner('inline-loading', 'none');
  fixture.controller.updateChapterBoundaries(); fixture.flushFrames();
  const after = fixture.emitted.filter((event) => event.name === 'scroll.progressPreview').at(-1).payload.progress;
  assert.equal(during, before);
  assert.equal(after, before);
  fixture.dispose();
});
