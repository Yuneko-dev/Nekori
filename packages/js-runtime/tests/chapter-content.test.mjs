import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeChapterContent } from '../src/plugins/helpers/chapterContent.ts';

test('LNReader is normalized upward; Nekori keeps an object', () => {
  const legacy = normalizeChapterContent('<p>Legacy</p>', false);
  assert.equal(legacy.state, 'ready'); assert.equal(legacy.type, 'novel'); assert.equal(legacy.html, '<p>Legacy</p>');
  const content = { state: 'ready', type: 'video', html: '<div>Player</div>', noCache: true };
  assert.deepEqual(normalizeChapterContent(content, true), content);
  assert.throws(() => normalizeChapterContent(content, false));
  assert.throws(() => normalizeChapterContent('string', true));
});
test('checkpoint flags are forced without injecting HTML markers', () => {
  const content = { state: 'checkpoint', type: 'mixed', html: '<div>Captcha</div>', checkpointMessage: 'Solve & retry' };
  assert.deepEqual(normalizeChapterContent(content, true), { ...content, noCache: true, noPrefetch: true });
});
test('legacy markers are read only in the legacy adapter', () => {
  const html = '<meta id="no-cache-marker"><meta id="no-prefetch-marker"><meta name="lnreader-chapter-type" content="video">';
  assert.deepEqual(normalizeChapterContent(html, false), { state: 'ready', type: 'video', noCache: true, noPrefetch: true, html });
  const native = normalizeChapterContent({ state: 'ready', type: 'novel', html }, true);
  assert.equal(native.type, 'novel'); assert.equal(native.noCache, undefined);
});
test('invalid fields fail at the boundary', () => {
  for (const extra of [{ state: 'bad' }, { type: 'audio' }, { noCache: 'false' }, { html: 1 }, { checkpointMessage: 3 }]) {
    assert.throws(() => normalizeChapterContent({ state: 'ready', type: 'novel', html: 'text', ...extra }, true));
  }
});
