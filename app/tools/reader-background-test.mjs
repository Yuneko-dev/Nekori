import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Reuse an existing Playwright installation; no browser dependency ships with the app.
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
    ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const assets = new URL('../src/main/assets/novel-reader/', import.meta.url);
const server = createServer(async (req, res) => {
    const name = new URL(req.url, 'http://localhost').pathname.slice(1);
    if (name === 'black.svg' || name === 'white.svg') {
        res.setHeader('Content-Type', 'image/svg+xml');
        res.end(`<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="${name.split('.')[0]}"/></svg>`);
    } else if (!name) {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<html><head><link rel="stylesheet" href="reader.css"><style>
            :root { --reader-background-color:#fff; --reader-text-color:#123456;
            --reader-font-size:20px; --reader-line-height:1.6; --reader-font-family:serif;
            --reader-margin-top:20px; --reader-margin-bottom:20px; --reader-margin-left:20px;
            --reader-margin-right:20px; --reader-paragraph-spacing:1em; }
            </style></head><body class="tsundoku-reader-force-style"><div id="LNReader-chapter">
            ${'<p>A calm chapter under the stars. The background stays still while reading.</p>'.repeat(60)}</div>
            <script>window.failures=[];window.reader={error:m=>failures.push(m)}</script>
            <script id="reader-background-config" type="application/json">{"image":""}</script>
            <script src="reader-background.js"></script></body></html>`);
    } else {
        try {
            res.setHeader('Content-Type', name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'text/javascript' : name.endsWith('.webp') ? 'image/webp' : 'image/png');
            res.end(await readFile(new URL(name, assets)));
        } catch { res.writeHead(404).end(); }
    }
});
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    const page = await browser.newPage({ viewport: { width: 412, height: 820 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin);
    const config = { image: `${origin}/black.svg`, size: 'cover', position: 'center', opacity: 100, blur: 0 };
    const apply = value => page.evaluate(value => readerBackground.apply(value), value);
    const color = () => page.evaluate(() => getComputedStyle(document.body).color);
    const waitColor = expected => page.waitForFunction(expected => getComputedStyle(document.body).color === expected, expected);
    await apply(config);
    await page.waitForFunction(() => getComputedStyle(document.getElementById('reader-background')).backgroundImage !== 'none');
    assert.equal(await color(), 'rgb(18, 52, 86)');
    assert.equal(await page.locator('#LNReader-chapter p').count(), 60);
    assert.equal(await page.locator('#reader-background').getAttribute('aria-hidden'), 'true');
    await page.evaluate(() => scrollTo(0, 500));
    assert.equal((await page.locator('#reader-background').boundingBox()).y, 0);
    await apply({ ...config, opacity: 0 });
    await waitColor('rgb(18, 52, 86)');
    await apply({ ...config, image: `${origin}/white.svg` });
    await waitColor('rgb(18, 52, 86)');
    await apply({ ...config, blur: 5, repeat: true, position: 'top', size: 'auto' });
    await waitColor('rgb(18, 52, 86)');
    await page.waitForFunction(() => getComputedStyle(document.getElementById('reader-background')).backgroundImage !== 'none');
    const style = await page.locator('#reader-background').evaluate(el => {
        const css = getComputedStyle(el);
        return [css.filter, css.backgroundRepeat, css.backgroundSize, css.pointerEvents];
    });
    assert.deepEqual(style, ['blur(5px)', 'repeat', 'auto', 'none']);
    for (const [size, repeat, position, expectedSize, expectedRepeat] of [
        ['cover', false, 'center', 'cover', 'no-repeat'],
        ['contain', false, 'center', 'contain', 'no-repeat'],
        ['stretch', false, 'center', '100% 100%', 'no-repeat'],
        ['auto', true, 'top left', 'auto', 'repeat'],
        ['auto', false, 'center', 'auto', 'no-repeat'],
    ]) {
        await apply({ ...config, size, repeat, position });
        const actual = await page.locator('#reader-background').evaluate(el => {
            const css = getComputedStyle(el);
            return [css.backgroundSize, css.backgroundRepeat];
        });
        assert.deepEqual(actual, [expectedSize, expectedRepeat]);
    }
    await apply({ ...config, image: `${origin}/backgrounds/default_01.webp` });
    await page.waitForFunction(() => getComputedStyle(document.getElementById('reader-background')).backgroundImage !== 'none');
    // System/status-bar reserves belong to prose margins, not the background viewport.
    await page.evaluate(() => {
        document.documentElement.style.setProperty('--reader-margin-top', '64px');
        document.documentElement.style.setProperty('--reader-margin-bottom', '72px');
        document.documentElement.style.setProperty('--reader-margin-left', '48px');
        scrollTo(0, 0);
    });
    assert.deepEqual(await page.locator('#reader-background').boundingBox(), { x: 0, y: 0, width: 412, height: 820 });
    assert.equal(await page.locator('body').evaluate(el => getComputedStyle(el).paddingTop), '64px');
    // Verify fixed decoration in the actual column CSS, retaining every paragraph.
    await page.evaluate(() => {
        scrollTo(0, 0);
        document.body.classList.add('page-reader');
        document.getElementById('LNReader-chapter').dataset.readerSpread = 'single';
    });
    assert.equal(await page.locator('#LNReader-chapter p').count(), 60);
    assert.deepEqual(await page.locator('#LNReader-chapter').evaluate(el => {
        const css = getComputedStyle(el);
        return [css.paddingTop, css.paddingBottom, css.paddingLeft];
    }), ['64px', '112px', '48px']);
    const first = await page.locator('#reader-background').boundingBox();
    await page.locator('#LNReader-chapter').evaluate(el => { el.scrollLeft = 400; });
    assert.deepEqual(await page.locator('#reader-background').boundingBox(), first);
    if (process.env.BACKGROUND_SCREENSHOT) await page.screenshot({ path: process.env.BACKGROUND_SCREENSHOT });
    await apply({ ...config, image: '' });
    await waitColor('rgb(18, 52, 86)');
    assert.equal(await page.locator('#reader-background').evaluate(el => getComputedStyle(el).backgroundImage), 'none');
    await apply({ ...config, image: `${origin}/missing.png`, errorMessage: 'Background unavailable' });
    await page.waitForFunction(() => failures.length === 1);
    assert.deepEqual(await page.evaluate(() => failures), ['Background unavailable']);
    assert.equal(await color(), 'rgb(18, 52, 86)');
    // An older request cannot override a newer selection or a disabled background.
    await page.evaluate(url => { readerBackground.apply({ image: url }); readerBackground.apply({ image: '' }); }, `${origin}/black.svg`);
    await page.waitForTimeout(100);
    assert.equal(await color(), 'rgb(18, 52, 86)');
    assert.deepEqual(errors, []);
    console.log('Reader backgrounds: preserved text colors, opacity, manual colors, blur/repeat, scrolling, columns, reset, errors and stale loads passed.');
} finally {
    await browser.close();
    await new Promise(resolve => server.close(resolve));
}
