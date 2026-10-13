import assert from 'node:assert/strict';
import test from 'node:test';
import {
  chapterForLeaf, leafRange, logicalOffset, physicalOffset, progressForUnit, unitCount,
  unitFromOffset, unitFromProgress,
} from '../../../tools/reader-runtime/src/layout/math.ts';
import { createLayoutCommands } from '../../../tools/reader-runtime/src/layout/commands.ts';

test('maps leaves into single and double visual units', () => {
  assert.equal(unitCount(5, 'single'), 5);
  assert.equal(unitCount(5, 'double'), 3);
  assert.deepEqual(leafRange(1, 5, 'double'), { firstLeaf: 3, lastLeaf: 4 });
  assert.deepEqual(leafRange(2, 5, 'double'), { firstLeaf: 5, lastLeaf: 5 });
});

test('rounds offsets and progress to bounded visual units', () => {
  assert.equal(unitFromOffset(149, 300, 4), 0);
  assert.equal(unitFromOffset(151, 300, 4), 1);
  assert.equal(unitFromOffset(9_999, 300, 4), 3);
  assert.equal(unitFromProgress(0.5, 4), 2);
  assert.equal(progressForUnit(2, 4), 2 / 3);
  assert.equal(progressForUnit(0, 1), 1);
});

test('converts logical offsets for Chromium LTR and RTL scrolling', () => {
  assert.equal(logicalOffset(320, 'ltr'), 320);
  assert.equal(logicalOffset(-320, 'rtl'), 320);
  assert.equal(physicalOffset(320, 'ltr'), 320);
  assert.equal(physicalOffset(320, 'rtl'), -320);
});

test('finds the chapter containing a leaf and clamps gaps to the nearest chapter', () => {
  const map = [
    { chapterId: 'a', startLeaf: 0, leafCount: 3 },
    { chapterId: 'b', startLeaf: 4, leafCount: 2 },
  ];
  assert.equal(chapterForLeaf(map, 2)?.chapterId, 'a');
  assert.equal(chapterForLeaf(map, 4)?.chapterId, 'b');
  assert.equal(chapterForLeaf(map, 3)?.chapterId, 'a');
  assert.equal(chapterForLeaf(map, 99)?.chapterId, 'b');
});

test('layout commands move and seek through the measured chapter map', () => {
  const calls = [];
  const config = { enabled: true, spread: 'single', direction: 'ltr', vertical: false,
    infinite: false, threshold: 0.8, chapterId: 'a' };
  const commands = createLayoutCommands({
    root: {}, getConfig: () => config,
    getPageMap: () => [{ chapterId: 'a', startLeaf: 0, leafCount: 5, element: {} }],
    getTotalLeaves: () => 5, getCurrentUnit: () => 1, perUnit: () => 1, viewport: () => 100,
    scrollToUnit: (...args) => calls.push(args), requestNext() {}, scheduleReflow() {}, emitPosition() {},
    onProgress() {}, transitions: { silent: false, restore: null, silentSourceUnit: null, lastChapterProgress: null },
  });
  assert.equal(commands.moveBy(1, 'instant'), true);
  assert.equal(commands.seekUnit(4, 'smooth'), true);
  assert.deepEqual(calls, [[2, 'instant'], [4, 'smooth']]);
});
