import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const assetUrl = new URL('../../main/assets/novel-reader/scroll-tracking.js', import.meta.url);

function createHarness() {
    const frames = [];
    const listeners = new Map();
    const chapterUpdates = [];
    let dividers = [divider('1', 0), divider('2', 1_000)];

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
    };
    const Android = {
        loadNextChapter() {},
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
        requestAnimationFrame(callback) {
            frames.push(callback);
        },
        setTimeout,
        window,
    });

    return {
        chapterUpdates,
        drainFrame() {
            assert.notEqual(frames.length, 0, 'expected a scheduled animation frame');
            frames.shift()(0);
        },
        replaceSecondDivider(chapterId) {
            dividers = [divider('1', 0), divider(chapterId, 1_000)];
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
                return { top: absoluteTop - window.scrollY };
            },
        };
    }
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
