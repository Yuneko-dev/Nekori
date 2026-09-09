import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Execute the injected script itself so the test covers the real WebView short-chapter probe.
const viewer = readFileSync(new URL(
    '../../main/java/eu/kanade/tachiyomi/ui/reader/viewer/text/webview/NovelWebViewViewer.kt',
    import.meta.url,
), 'utf8');
const script = viewer.match(/private fun syncShortChapterProgressIfNeeded\(\)[\s\S]*?"""([\s\S]*?)"""/)[1]
    .replaceAll('$TSUNDOKU_OBJECT_NAME', 'Tsundoku');

function probe(paged, height) {
    let marks = 0;
    const timers = [];
    vm.runInNewContext(script, {
        window: { innerHeight: 800, Tsundoku: { runtime: { readerLayout: { enabled: paged } } } },
        document: { documentElement: { scrollHeight: height, clientHeight: 800 }, body: { scrollHeight: height } },
        Android: { markChapterAsShort() { marks++; } },
        ResizeObserver: class { observe() {} disconnect() {} },
        setTimeout(callback) { timers.push(callback); },
    });
    timers.forEach(callback => callback());
    return marks;
}

test('a paged document fitting the vertical viewport is not marked 100 percent', () => {
    assert.equal(probe(true, 800), 0);
});

test('only a short vertical chapter is automatically completed', () => {
    assert.equal(probe(false, 800), 1);
    assert.equal(probe(false, 4_000), 0);
});
