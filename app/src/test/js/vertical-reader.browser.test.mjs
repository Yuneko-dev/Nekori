import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';

const asset = name => readFileSync(new URL(`../../main/assets/novel-reader/${name}`, import.meta.url), 'utf8');

test('vertical multilingual pages preserve text, margins, navigation and chapter anchors', {
    skip: !process.env.CHROMIUM_PATH,
}, async () => {
    const { chromium } = createRequire(import.meta.url)('playwright');
    const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH });
    try {
        const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
        await page.setContent(`<style>
            :root { --reader-margin-top: 30px; --reader-margin-bottom: 20px;
                --reader-margin-left: 20px; --reader-margin-right: 20px;
                --reader-font-size: 20px; --reader-line-height: 1.6;
                --reader-paragraph-spacing: 12px; --reader-paragraph-indent: 0; }
            ${asset('reader.css')}
        </style><div id="LNReader-chapter"><tsundoku-chapter data-chapter-id="1" dir="ltr">
            <p id="start">「縦書きー」小さいゃゅょ <span class="tcy">12</span> 34 123 A12B 中文 한국어 English Tiếng Việt</p>
            ${'<p>日本語の文章、中文直排，한국어 문장. English and Tiếng Việt text.</p>'.repeat(80)}
            <p id="end">終わり</p></tsundoku-chapter></div>`);
        const original = await page.locator('#LNReader-chapter').textContent();
        await page.evaluate(() => {
            window.positions = [];
            window.Android = { onPagePositionChanged: (...args) => window.positions.push(args) };
        });
        await page.addScriptTag({ content: asset('reader-layout.js').replaceAll('__TSUNDOKU_OBJECT_NAME__', 'Tsundoku') });
        await page.addScriptTag({ content: asset('page-reader.js') });
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.configure({
            enabled: true, vertical: true, direction: 'rtl', spread: 'double', chapterId: '1',
        }));
        await page.waitForTimeout(300);
        const initial = await page.evaluate(() => {
            const host = document.getElementById('LNReader-chapter');
            return {
                mode: getComputedStyle(host).writingMode,
                combine: getComputedStyle(document.querySelector('.tcy')).textCombineUpright,
                width: host.clientWidth, height: host.clientHeight,
                extent: host.scrollHeight, pages: window.pageReader?.totalPages,
            };
        });
        assert.equal(initial.mode, 'vertical-rl');
        assert.equal(initial.combine, 'all');
        assert.deepEqual(await page.locator('.tcy').allTextContents(), ['12', '34']);
        assert.equal(initial.width, 384);
        assert.equal(initial.height, 832);
        assert.ok(initial.extent > initial.height * 2, JSON.stringify(initial));
        const flow = await page.evaluate(() => {
            const start = document.getElementById('start');
            const range = document.createRange();
            range.setStart(start.firstChild, 1);
            range.setEnd(start.firstChild, 2);
            const first = range.getBoundingClientRect();
            range.setStart(start.firstChild, 2);
            range.setEnd(start.firstChild, 3);
            const next = range.getBoundingClientRect();
            return {
                downward: next.top > first.top,
                sameColumn: Math.abs(first.left - next.left) < 1,
                nextColumnOnLeft: start.nextElementSibling.getBoundingClientRect().right <= start.getBoundingClientRect().left,
            };
        });
        assert.deepEqual(flow, { downward: true, sameColumn: true, nextColumnOnLeft: true });
        if (process.env.READER_SCREENSHOT) await page.screenshot({ path: process.env.READER_SCREENSHOT });
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.moveBy(1));
        await page.waitForTimeout(250);
        assert.equal(await page.locator('#LNReader-chapter').evaluate(e => e.scrollTop), 832);
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.revealElement(document.getElementById('end')));
        await page.waitForTimeout(250);
        const end = await page.locator('#end').boundingBox();
        assert.ok(end.y >= 29 && end.y < 772 && end.x >= 0 && end.x < 384, JSON.stringify(end));
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.seekPercent(0));
        await page.waitForTimeout(250);
        assert.equal(await page.locator('#LNReader-chapter').evaluate(e => e.scrollTop), 0);
        assert.equal(await page.locator('#LNReader-chapter').textContent(), original);
        assert.equal(await page.evaluate(() => window.scrollY), 0);

        // Reflow uses the existing chapter-local progress model, including after a screen rotation.
        await page.setViewportSize({ width: 900, height: 500 });
        await page.waitForTimeout(300);
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.seekPercent(100));
        await page.waitForTimeout(250);
        const rotatedEnd = await page.locator('#end').boundingBox();
        assert.ok(rotatedEnd.y >= 29 && rotatedEnd.y < 440 && rotatedEnd.x >= 0 && rotatedEnd.x < 900,
            JSON.stringify(rotatedEnd));

        // Appending preserves chapter attribution; revealElement is also the TTS/footnote path.
        await page.evaluate(() => {
            const chapter = document.createElement('tsundoku-chapter');
            chapter.dataset.chapterId = '2';
            chapter.innerHTML = '<p id="second">第二章 56 中文 한국어 English</p>' + '<p>多言語の文章。</p>'.repeat(70);
            document.getElementById('LNReader-chapter').appendChild(chapter);
        });
        await page.waitForTimeout(300);
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.revealElement(document.getElementById('second')));
        await page.waitForTimeout(250);
        assert.equal(await page.evaluate(() => window.positions.at(-1)[0]), '2');
        assert.equal(await page.evaluate(() => window.positions.at(-1)[1]), 0);
        assert.equal(await page.locator('#second .tcy').textContent(), '56');
        const second = await page.locator('#second').boundingBox();
        assert.ok(second.y >= 29 && second.y < 440, JSON.stringify(second));
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.reflow());
        await page.waitForTimeout(250);
        assert.equal(await page.evaluate(() => window.positions.at(-1)[0]), '2');
        assert.equal(await page.evaluate(() => window.positions.at(-1)[1]), 0);

        // One-page chapters are complete, and changing back restores ordinary horizontal columns.
        await page.evaluate(() => {
            document.getElementById('LNReader-chapter').innerHTML = '<tsundoku-chapter data-chapter-id="3"><p>短い章。</p></tsundoku-chapter>';
        });
        await page.waitForTimeout(300);
        assert.equal(await page.evaluate(() => window.positions.at(-1)[2]), 1);
        await page.evaluate(() => window.Tsundoku.runtime.readerLayout.configure({ enabled: true }));
        await page.waitForTimeout(250);
        assert.equal(await page.locator('#LNReader-chapter').evaluate(e => getComputedStyle(e).writingMode), 'horizontal-tb');
    } finally {
        await browser.close();
    }
});
