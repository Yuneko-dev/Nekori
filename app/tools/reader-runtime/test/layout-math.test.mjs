import assert from 'node:assert/strict';
import test from 'node:test';
import {
  chapterForLeaf,
  leafRange,
  logicalOffset,
  physicalOffset,
  progressForUnit,
  unitCount,
  unitFromOffset,
  unitFromProgress,
} from '../src/layout/math.ts';

test('single and double spreads retain blank trailing leaves as one unit', () => {
  assert.equal(unitCount(0, 'single'), 1);
  assert.equal(unitCount(3, 'double'), 2);
  assert.deepEqual(leafRange(1, 3, 'double'), { firstLeaf: 3, lastLeaf: 3 });
});

test('page position and progress clamp to valid bounds', () => {
  assert.equal(unitFromOffset(1000, 100, 4), 3);
  assert.equal(unitFromOffset(-10, 100, 4), 0);
  assert.equal(unitFromProgress(2, 4), 3);
  assert.equal(progressForUnit(0, 1), 1);
});

test('RTL offsets use logical coordinates while vertical uses scrollTop externally', () => {
  assert.equal(logicalOffset(-240, 'rtl'), 240);
  assert.equal(physicalOffset(240, 'rtl'), -240);
  assert.equal(logicalOffset(240, 'ltr'), 240);
});

test('leaf map chooses the chapter owning the leaf, including gaps', () => {
  const pages = [
    { chapterId: '1', startLeaf: 0, leafCount: 3 },
    { chapterId: '2', startLeaf: 4, leafCount: 2 },
  ];
  assert.equal(chapterForLeaf(pages, 3)?.chapterId, '1');
  assert.equal(chapterForLeaf(pages, 4)?.chapterId, '2');
  assert.equal(chapterForLeaf([], 0), null);
});
