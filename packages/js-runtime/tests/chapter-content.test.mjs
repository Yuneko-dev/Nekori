import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeChapterContent } from '../src/plugins/helpers/chapterContent.ts';

test('LNReader strings pass through; Nekori keeps an object', () => {
  assert.equal(normalizeChapterContent('<p>Legacy</p>', false), '<p>Legacy</p>');
  const content = { state: 'ready', type: 'video', html: '<div>Player</div>', noCache: true };
  assert.deepEqual(normalizeChapterContent(content, true), content);
  assert.throws(() => normalizeChapterContent(content, false));
  assert.throws(() => normalizeChapterContent('string', true));
});
test('checkpoint passes through; deep module forces flags', () => {
  const content = {
    state: 'checkpoint',
    type: 'mixed',
    html: '<div>Captcha</div>',
    checkpointMessage: 'Solve & retry',
  };
  assert.deepEqual(normalizeChapterContent(content, true), content);
});
test('invalid fields fail at the interface', () => {
  for (const extra of [
    { state: 'bad' },
    { type: 'audio' },
    { noCache: 'false' },
    { html: 1 },
    { checkpointMessage: 3 },
  ]) {
    assert.throws(() =>
      normalizeChapterContent({ state: 'ready', type: 'novel', html: 'text', ...extra }, true),
    );
  }
});
