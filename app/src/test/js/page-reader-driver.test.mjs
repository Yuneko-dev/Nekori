import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const layoutUrl = new URL('../../main/assets/novel-reader/reader-layout.js', import.meta.url);
const driverUrl = new URL('../../main/assets/novel-reader/page-reader.js', import.meta.url);
const readerCssUrl = new URL('../../main/assets/novel-reader/reader.css', import.meta.url);

function createHarness({ spread = 'single', direction = 'ltr', scrollWidth = 300, withBridge = true } = {}) {
    const frames = [];
    const positions = [];
    const persisted = [];
    let previousLoads = 0;
    let nextLoads = 0;
    const listeners = {};
    const pageReader = { page: { val: 0 }, totalPages: { val: 0 } };
    const container = {
        clientWidth: 100,
        scrollWidth,
        scrollLeft: 0,
        dataset: {},
        removeAttribute(name) { if (name === 'dir') delete this.dir; },
        addEventListener(name, callback) { listeners[name] = callback; },
        appendChild() {},
        getBoundingClientRect() { return { left: 0, right: 100 }; },
        querySelectorAll() { return []; },
        scrollTo(options) { this.scrollLeft = options.left; },
    };
    const document = {
        body: { classList: { toggle() {} } },
        fonts: null,
        addEventListener() {},
        createElement() { throw new Error('single-page test must not create blank leaves'); },
        getElementById() { return container; },
    };
    const Android = {
        loadPreviousChapter() { previousLoads += 1; },
        loadNextChapter() { nextLoads += 1; },
        onChapterScrollUpdate() {},
        onPagePositionChanged(...args) { positions.push(args); },
        onScrollProgress(value) { persisted.push(value); },
        onScrollUpdate() {},
    };
    const window = { document, pageReader, Tsundoku: { runtime: {} } };
    if (withBridge) window.Android = Android;
    const context = {
        Array,
        Math,
        MutationObserver: class { observe() {} },
        Number,
        ResizeObserver: class { observe() {} },
        String,
        clearTimeout,
        document,
        requestAnimationFrame(callback) { frames.push(callback); },
        setTimeout,
        window,
    };
    vm.runInNewContext(
        readFileSync(layoutUrl, 'utf8').replaceAll('__TSUNDOKU_OBJECT_NAME__', 'Tsundoku'),
        context,
    );
    vm.runInNewContext(readFileSync(driverUrl, 'utf8'), context);
    const layout = window.Tsundoku.runtime.readerLayout;
    layout.configure({ enabled: true, spread, direction, infinite: false, chapterId: '7' });
    frames.shift()();
    return {
        layout,
        container,
        runtime: window.Tsundoku.runtime,
        pageReader,
        positions,
        persisted,
        get previousLoads() { return previousLoads; },
        get nextLoads() { return nextLoads; },
        triggerScroll() { listeners.scroll(); },
    };
}

test('reports chapter-local page state through the existing bridge and pageReader object', () => {
    const harness = createHarness();

    assert.deepEqual(harness.positions[0], ['7', 0, 3, 1, 1, 3]);
    assert.equal(harness.pageReader.page.val, 1);
    assert.equal(harness.pageReader.totalPages.val, 3);
});

test('clamps paged scrolling to the first and last complete visual units', () => {
    const harness = createHarness();

    harness.container.scrollLeft = 999;
    harness.triggerScroll();
    assert.equal(harness.container.scrollLeft, 200);

    harness.container.scrollLeft = -50;
    harness.triggerScroll();
    assert.equal(harness.container.scrollLeft, 0);
});

test('paged layout disables overscroll and fixes the column viewport height', () => {
    const css = readFileSync(readerCssUrl, 'utf8');

    assert.match(css, /body\.page-reader\s*\{[^}]*overscroll-behavior:\s*none/s);
    assert.match(css, /body\.page-reader #LNReader-chapter\s*\{[^}]*max-block-size:\s*100dvh/s);
    assert.match(css, /body\.page-reader #LNReader-chapter\s*\{[^}]*overflow-y:\s*hidden/s);
});

test('does not load a previous chapter from the first visual unit', () => {
    const harness = createHarness();

    harness.layout.moveBy(-1, 'instant');
    harness.layout.seekUnit(2);
    harness.layout.moveBy(1, 'instant');

    assert.equal(harness.previousLoads, 0);
    assert.equal(harness.nextLoads, 1);
});

test('curl commit keeps the target unit and emits one persisted position', () => {
    const harness = createHarness();
    const before = harness.positions.length;

    harness.layout.prepareSilentTurn(false);
    harness.layout.moveBy(1, 'instant');
    harness.layout.finishSilentTurn(false, true);

    assert.equal(harness.positions.length, before + 1);
    assert.deepEqual(harness.positions.at(-1), ['7', 1, 3, 2, 2, 3]);
    assert.deepEqual(harness.persisted, [0.5]);
});

test('curl rollback returns to the source without emitting progress', () => {
    const harness = createHarness();
    const before = harness.positions.length;

    harness.layout.prepareSilentTurn(false);
    harness.layout.moveBy(1, 'instant');
    harness.layout.finishSilentTurn(false, false);

    assert.equal(harness.positions.length, before);
    assert.deepEqual(harness.persisted, []);
});

test('curl preview stays anchored to one visual unit', () => {
    const harness = createHarness();

    harness.layout.prepareSilentTurn(false);
    harness.layout.moveBy(1, 'instant');
    harness.layout.moveBy(1, 'instant');

    assert.equal(harness.container.scrollLeft, 100);
});

test('a latched incompatible chapter can still navigate at the page boundary', () => {
    const harness = createHarness();
    harness.layout.configure({ enabled: true, spread: 'single', direction: 'ltr', infinite: true, chapterId: '7' });
    harness.runtime.noMoreChapters = true;
    harness.layout.seekUnit(2);
    harness.layout.finishSilentTurn(false, false);
    harness.layout.moveBy(1, 'instant');

    assert.equal(harness.nextLoads, 1);
});

test('book direction is removed when pagination is disabled', () => {
    const harness = createHarness();
    harness.layout.configure({ enabled: true, spread: 'single', direction: 'rtl', infinite: false, chapterId: '7' });
    assert.equal(harness.container.dir, 'rtl');

    harness.layout.configure({ enabled: false, spread: 'single', direction: 'rtl', infinite: false, chapterId: '7' });
    assert.equal(harness.container.dir, undefined);
});

test('moves one full visual unit in LTR and mirrored RTL double-page layouts', () => {
    const ltr = createHarness({ spread: 'double', direction: 'ltr', scrollWidth: 400 });
    ltr.layout.moveBy(1, 'instant');
    ltr.layout.finishSilentTurn(false, false);
    assert.equal(ltr.container.scrollLeft, 100);
    assert.deepEqual(ltr.positions.at(-1), ['7', 1, 4, 3, 4, 8]);

    const rtl = createHarness({ spread: 'double', direction: 'rtl', scrollWidth: 400 });
    rtl.layout.moveBy(1, 'instant');
    rtl.layout.finishSilentTurn(false, false);
    assert.equal(rtl.container.scrollLeft, -100);
    assert.deepEqual(rtl.positions.at(-1), ['7', 1, 4, 3, 4, 8]);
});

test('preview can install the shared page engine without an Android bridge', () => {
    const harness = createHarness({ withBridge: false });

    assert.equal(harness.pageReader.page.val, 1);
    assert.equal(harness.pageReader.totalPages.val, 3);
});
