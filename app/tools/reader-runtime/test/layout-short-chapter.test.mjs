import assert from 'node:assert/strict';
import test from 'node:test';
import { observeShortChapter } from '../src/layout/index.ts';

function shortChapterFixture({ height, paged = false }) {
  let resize;
  let timeout;
  let disconnected = 0;
  const view = {
    innerHeight: 100,
    ResizeObserver: class {
      constructor(callback) { resize = callback; }
      observe() {}
      disconnect() { disconnected += 1; }
    },
    setTimeout(callback, delay) { timeout = callback; assert.equal(delay, 500); return 7; },
    clearTimeout() { timeout = null; },
  };
  const document = {
    defaultView: view,
    documentElement: { scrollHeight: height, clientHeight: 100 },
    body: { scrollHeight: height },
  };
  const root = {
    ownerDocument: document,
    classList: { contains: (name) => paged && name === 'is-paged' },
  };
  const emitted = [];
  const cleanup = observeShortChapter({ host: { root, emit: (name, payload) => emitted.push({ name, payload }) }, enabled: true });
  return { emitted, cleanup, flushResize: () => resize?.(), flushTimeout: () => timeout?.(), disconnected };
}

test('initial short chapter emits markShort only once across resize and timeout checks', () => {
  const fixture = shortChapterFixture({ height: 100 });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, [{ name: 'chapter.markShort', payload: {} }]);
});

test('a chapter that overflows after layout settles is not marked short', () => {
  const fixture = shortChapterFixture({ height: 101 });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, []);
});

test('paged layout skips the short chapter probe', () => {
  const fixture = shortChapterFixture({ height: 1, paged: true });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, []);
});
