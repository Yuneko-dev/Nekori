import assert from 'node:assert/strict';
import test from 'node:test';
import { parseReaderConfig } from '../src/protocol/reader-config.ts';

function validConfig() {
  return {
    documentId: 'doc-1',
    mode: 'reader',
    novel: { id: 'novel', name: 'Novel', path: '/novel' },
    chapter: { id: 'chapter', name: 'Chapter', path: '//opaque/#fragment', progress: 0.2 },
    nextChapter: null,
    strings: { retry: 'Retry' },
    autoSaveInterval: 5000,
    runtime: {
      editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
      menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
      loadingNext: false, noMoreChapters: false,
    },
    chapters: [],
  };
}

test('validates config while preserving opaque chapter paths', () => {
  const config = validConfig();
  const parsed = parseReaderConfig(config);
  assert.equal(parsed.chapter.path, '//opaque/#fragment');
});

test('rejects malformed chapters and direct player configs', () => {
  const invalidChapter = validConfig();
  invalidChapter.chapter.progress = Number.NaN;
  assert.throws(() => parseReaderConfig(invalidChapter), /chapter|config/i);

  const invalidPlayer = validConfig();
  invalidPlayer.player = { mode: 'playback', video: { direct: true, path: '/v', progress: 0 } };
  assert.throws(() => parseReaderConfig(invalidPlayer), /player/i);
});
