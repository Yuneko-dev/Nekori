import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const assetUrl = new URL('../../main/assets/novel-reader/reader-layout.js', import.meta.url);
const pageReaderUrl = new URL('../../main/assets/novel-reader/page-reader.js', import.meta.url);

function loadMath() {
    const window = { Tsundoku: { runtime: {} } };
    const source = readFileSync(assetUrl, 'utf8')
        .replaceAll('__TSUNDOKU_OBJECT_NAME__', 'Tsundoku');
    vm.runInNewContext(source, { window });
    return window.Tsundoku.runtime.readerLayout._math;
}

function loadLayout() {
    const window = { Tsundoku: { runtime: {} } };
    const source = readFileSync(assetUrl, 'utf8')
        .replaceAll('__TSUNDOKU_OBJECT_NAME__', 'Tsundoku');
    vm.runInNewContext(source, { window });
    return window.Tsundoku.runtime.readerLayout;
}

test('maps leaves into single and double visual units', () => {
    const math = loadMath();

    assert.equal(math.unitCount(5, 'single'), 5);
    assert.equal(math.unitCount(5, 'double'), 3);
    assert.deepEqual({ ...math.leafRange(1, 5, 'double') }, {
        firstLeaf: 3,
        lastLeaf: 4,
    });
    assert.deepEqual({ ...math.leafRange(2, 5, 'double') }, {
        firstLeaf: 5,
        lastLeaf: 5,
    });
});

test('rounds offsets and progress to bounded visual units', () => {
    const math = loadMath();

    assert.equal(math.unitFromOffset(149, 300, 4), 0);
    assert.equal(math.unitFromOffset(151, 300, 4), 1);
    assert.equal(math.unitFromOffset(9_999, 300, 4), 3);
    assert.equal(math.unitFromProgress(0.5, 4), 2);
    assert.equal(math.progressForUnit(2, 4), 2 / 3);
    assert.equal(math.progressForUnit(0, 1), 1);
});

test('converts logical offsets for Chromium LTR and RTL scrolling', () => {
    const math = loadMath();

    assert.equal(math.logicalOffset(320, 'ltr'), 320);
    assert.equal(math.logicalOffset(-320, 'rtl'), 320);
    assert.equal(math.physicalOffset(320, 'ltr'), 320);
    assert.equal(math.physicalOffset(320, 'rtl'), -320);
});

test('finds the chapter containing a leaf and clamps gaps to the nearest chapter', () => {
    const math = loadMath();
    const map = [
        { chapterId: 'a', startLeaf: 0, leafCount: 3 },
        { chapterId: 'b', startLeaf: 4, leafCount: 2 },
    ];

    assert.equal(math.chapterForLeaf(map, 2).chapterId, 'a');
    assert.equal(math.chapterForLeaf(map, 4).chapterId, 'b');
    assert.equal(math.chapterForLeaf(map, 3).chapterId, 'a');
    assert.equal(math.chapterForLeaf(map, 99).chapterId, 'b');
});

test('forwards optional page movement and unit seek commands to the installed driver', () => {
    const layout = loadLayout();
    const calls = [];
    layout._install({
        moveBy(...args) { calls.push(['moveBy', ...args]); },
        seekUnit(...args) { calls.push(['seekUnit', ...args]); },
    });

    layout.moveBy(-1, 'instant');
    layout.seekUnit(4, 'smooth');

    assert.deepEqual(calls, [
        ['moveBy', -1, 'instant'],
        ['seekUnit', 4, 'smooth'],
    ]);
});

test('page driver is directly loadable without Kotlin token substitution', () => {
    assert.doesNotMatch(readFileSync(pageReaderUrl, 'utf8'), /__TSUNDOKU_OBJECT_NAME__/);
});
