import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const assetUrl = new URL('../../main/assets/novel-reader/scroll-tracking.js', import.meta.url);

function createHarness() {
    const frames = [];
    const listeners = new Map();
    const chapterUpdates = [];
    let nextLoads = 0;
    let dividers = [divider('1', 0), divider('2', 1_000)];
    let banner = null;
    let chapterOffset = 0;

    const window = {
        innerHeight: 800,
        scrollY: 0,
        pageYOffset: 0,
        chapterBoundaries: [],
        addEventListener(type, listener) {
            const typeListeners = listeners.get(type) ?? [];
            typeListeners.push(listener);
            listeners.set(type, typeListeners);
        },
        dispatchEvent(event) {
            for (const listener of listeners.get(event.type) ?? []) listener(event);
        },
    };
    const body = {
        scrollHeight: 4_000,
        scrollTop: 0,
        children: [],
    };
    const document = {
        body,
        documentElement: {
            clientHeight: 800,
            scrollHeight: 4_000,
            scrollTop: 0,
        },
        querySelectorAll() {
            return dividers;
        },
        getElementById(id) {
            return banner?.id === id ? banner : null;
        },
    };
    const Android = {
        loadNextChapter() {
            nextLoads += 1;
        },
        onChapterScrollUpdate(chapterId) {
            chapterUpdates.push(chapterId);
        },
        onScrollProgress() {},
        onScrollUpdate() {},
    };

    const source = readFileSync(assetUrl, 'utf8')
        .replaceAll('__TSUNDOKU_OBJECT_NAME__', 'Tsundoku')
        .replaceAll('__CHAPTER_DIVIDER_CLASS__', 'chapter-divider')
        .replaceAll('__CHAPTER_ID_ATTR__', 'data-chapter-id')
        .replaceAll('__INFINITE_SCROLL_ENABLED__', 'true')
        .replaceAll('__LOAD_THRESHOLD__', '0.8')
        .replaceAll('__DONE_THRESHOLD__', '0.99')
        .replaceAll('__PROGRESS_EVENT__', 'tsundoku-progress');

    vm.runInNewContext(source, {
        Android,
        CustomEvent: class CustomEvent {
            constructor(type, init) {
                this.type = type;
                this.detail = init?.detail;
            }
        },
        Date,
        clearTimeout,
        console,
        document,
        Node: { DOCUMENT_POSITION_FOLLOWING: 4 },
        requestAnimationFrame(callback) {
            frames.push(callback);
        },
        setTimeout,
        window,
    });

    return {
        chapterUpdates,
        get nextLoads() {
            return nextLoads;
        },
        drainFrame() {
            assert.notEqual(frames.length, 0, 'expected a scheduled animation frame');
            frames.shift()(0);
        },
        replaceSecondDivider(chapterId) {
            dividers = [divider('1', 0), divider(chapterId, 1_000)];
            syncDom();
        },
        setBanner(id, placement) {
            const height = 100;
            chapterOffset = placement === 'leading' ? height : 0;
            body.scrollHeight = placement === 'none' ? 4_000 : 4_000 + height;
            banner = placement === 'none' ? null : bannerNode(id, placement === 'leading' ? 0 : 4_000);
            dividers = [divider('1', 0), divider('2', 1_000)];
            syncDom();
        },
        boundaries() {
            return Array.from(window.chapterBoundaries, ({ chapterId, startOffset, height }) => ({
                chapterId,
                startOffset,
                height,
            }));
        },
        runtime: window.Tsundoku.runtime,
        scrollTo(y) {
            window.scrollY = y;
            window.dispatchEvent({ type: 'scroll' });
        },
        updateBoundaries() {
            window.updateChapterBoundaries();
        },
    };

    function divider(chapterId, absoluteTop) {
        return {
            getAttribute() {
                return chapterId;
            },
            getBoundingClientRect() {
                return { top: absoluteTop + chapterOffset - window.scrollY };
            },
            compareDocumentPosition(other) {
                return body.children.indexOf(this) < body.children.indexOf(other) ? 4 : 2;
            },
        };
    }

    function bannerNode(id, absoluteTop) {
        return {
            id,
            getBoundingClientRect() {
                return { top: absoluteTop - window.scrollY };
            },
        };
    }

    function syncDom() {
        body.children = banner?.id && chapterOffset > 0
            ? [banner, ...dividers]
            : [...dividers, ...(banner ? [banner] : [])];
    }

    syncDom();
}

function finishInitialFrames(harness) {
    harness.drainFrame();
    harness.drainFrame();
    assert.deepEqual(harness.chapterUpdates, ['1']);
}

test('reports a new stable chapter id when a boundary keeps the same numeric index', () => {
    const harness = createHarness();
    finishInitialFrames(harness);

    harness.scrollTo(1_000);
    harness.drainFrame();
    assert.deepEqual(harness.chapterUpdates, ['1', '2']);

    harness.replaceSecondDivider('3');
    harness.updateBoundaries();
    harness.drainFrame();

    assert.deepEqual(harness.chapterUpdates, ['1', '2', '3']);
});

test('resetChapterTracking immediately resamples a stationary viewport', () => {
    const harness = createHarness();
    finishInitialFrames(harness);

    harness.scrollTo(1_000);
    harness.drainFrame();
    assert.deepEqual(harness.chapterUpdates, ['1', '2']);

    harness.runtime.resetChapterTracking();
    harness.drainFrame();

    assert.deepEqual(harness.chapterUpdates, ['1', '2', '2']);
});

test('moving backward never requests a chapter outside the DOM', () => {
    const harness = createHarness();
    finishInitialFrames(harness);

    harness.scrollTo(300);
    harness.drainFrame();
    harness.scrollTo(100);
    harness.drainFrame();

    assert.equal(typeof harness.runtime.loadingPrevious, 'undefined');
});

test('leaves progress and chapter loading to the active paged layout', () => {
    const harness = createHarness();
    finishInitialFrames(harness);
    harness.runtime.readerLayout = { enabled: true };

    harness.scrollTo(3_200);
    harness.drainFrame();

    assert.deepEqual(harness.chapterUpdates, ['1']);
    assert.equal(harness.nextLoads, 0);
});

test('excludes trailing loading and error banners from the last chapter boundary', () => {
    for (const id of ['inline-loading', 'inline-error']) {
        const harness = createHarness();
        finishInitialFrames(harness);

        harness.setBanner(id, 'trailing');
        harness.updateBoundaries();

        assert.deepEqual(harness.boundaries(), [
            { chapterId: '1', startOffset: 0, height: 1_000 },
            { chapterId: '2', startOffset: 1_000, height: 3_000 },
        ]);
    }
});

test('ignores a leading prepend banner when rebuilding chapter boundaries', () => {
    const harness = createHarness();
    finishInitialFrames(harness);

    harness.setBanner('inline-loading', 'leading');
    harness.updateBoundaries();

    assert.deepEqual(harness.boundaries(), [
        { chapterId: '1', startOffset: 100, height: 1_000 },
        { chapterId: '2', startOffset: 1_100, height: 3_000 },
    ]);
});

test('keeps last-chapter progress stable while a trailing banner appears and disappears', () => {
    const harness = createHarness();
    finishInitialFrames(harness);

    harness.scrollTo(2_600);
    harness.drainFrame();
    const before = harness.runtime.chapterProgress;

    harness.setBanner('inline-loading', 'trailing');
    harness.updateBoundaries();
    harness.drainFrame();
    const during = harness.runtime.chapterProgress;

    harness.setBanner('inline-loading', 'none');
    harness.updateBoundaries();
    harness.drainFrame();
    const after = harness.runtime.chapterProgress;

    assert.equal(during, before);
    assert.equal(after, before);
});
