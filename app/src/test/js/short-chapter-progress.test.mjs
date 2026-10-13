import assert from 'node:assert/strict';
import test from 'node:test';
import { observeShortChapter } from '../../../tools/reader-runtime/src/layout/short-chapter.ts';

function shortChapterFixture({ height, paged = false }) {
  let resize;
  let timeout;
  let disconnected = 0;
  const view = {
    innerHeight: 800,
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
    documentElement: { scrollHeight: height, clientHeight: 800 },
    body: { scrollHeight: height },
  };
  const root = {
    ownerDocument: document,
    classList: { contains: (name) => paged && name === 'is-paged' },
  };
  const emitted = [];
  const cleanup = observeShortChapter({
    host: { root, emit: (name, payload) => emitted.push({ name, payload }) },
    enabled: true,
  });
  return {
    emitted, cleanup,
    flushResize: () => resize?.(),
    flushTimeout: () => timeout?.(),
    disconnected: () => disconnected,
  };
}

test('a short vertical chapter is marked once after layout settles', () => {
  const fixture = shortChapterFixture({ height: 800 });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, [{ name: 'chapter.markShort', payload: {} }]);
  assert.equal(fixture.disconnected(), 1);
});

test('an overflowing chapter is not marked as complete', () => {
  const fixture = shortChapterFixture({ height: 801 });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, []);
});

test('paged layout skips the short-chapter probe', () => {
  const fixture = shortChapterFixture({ height: 1, paged: true });
  fixture.flushResize();
  fixture.flushTimeout();
  assert.deepEqual(fixture.emitted, []);
});
