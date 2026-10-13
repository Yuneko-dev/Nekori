import assert from 'node:assert/strict';
import test from 'node:test';
import { computeVerticalProgress, shouldLoadNextChapter, boundariesFromOffsets } from '../src/layout/progress.ts';

test('vertical progress attributes a short trailing chapter at the reachable document bottom', () => {
  const boundaries = [
    { chapterId: 'first', startOffset: 0, height: 500 },
    { chapterId: 'short-last', startOffset: 500, height: 80 },
  ];
  const progress = computeVerticalProgress(480, 580, 100, boundaries, true, 0.95);
  assert.equal(progress.progress, 1);
  assert.equal(progress.chapterId, 'short-last');
  assert.equal(progress.chapterProgress, 1);
  assert.equal(progress.isLast, true);
  assert.equal(shouldLoadNextChapter(progress, boundaries, 0.8), true);
});

test('middle chapter progress ends at its next divider without subtracting viewport', () => {
  const boundaries = [
    { chapterId: 'one', startOffset: 0, height: 500 },
    { chapterId: 'two', startOffset: 500, height: 300 },
    { chapterId: 'three', startOffset: 800, height: 400 },
  ];
  const progress = computeVerticalProgress(650, 1200, 100, boundaries, true, 0.99);
  assert.equal(progress.chapterId, 'two');
  assert.equal(progress.chapterProgress, 0.5);
  assert.equal(progress.isLast, false);
});

test('boundary construction uses next chapter offsets and the actual document end', () => {
  assert.deepEqual(boundariesFromOffsets(['a', 'b'], [10, 90], 230), [
    { chapterId: 'a', startOffset: 10, height: 80 },
    { chapterId: 'b', startOffset: 90, height: 140 },
  ]);
});

test('single known chapter uses whole-document load threshold', () => {
  const boundaries = [{ chapterId: 'only', startOffset: 0, height: 1000 }];
  const state = computeVerticalProgress(850, 1000, 100, boundaries, true, 0.95);
  assert.equal(shouldLoadNextChapter(state, boundaries, 0.8), true);
  assert.equal(shouldLoadNextChapter(state, boundaries, 0), true);
});
