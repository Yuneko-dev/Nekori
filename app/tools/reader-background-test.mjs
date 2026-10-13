import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const runtimeDir = path.resolve('app/tools/reader-runtime');
const { build } = await import(pathToFileURL(path.join(runtimeDir, 'node_modules/esbuild/lib/main.js')).href);
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href
  : pathToFileURL(path.join(runtimeDir, 'node_modules/playwright/index.mjs')).href);
const inlineCss = {
  name: 'reader-background-test-inline-css',
  setup(builder) {
    builder.onResolve({ filter: /\.css\?inline$/ }, args => ({
      path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
      namespace: 'reader-background-test-css',
    }));
    builder.onLoad({ filter: /.*/, namespace: 'reader-background-test-css' }, async args => ({
      contents: await readFile(args.path, 'utf8'),
      loader: 'text',
    }));
  },
};

const css = await build({
  absWorkingDir: runtimeDir,
  entryPoints: ['src/styles/reader.css'],
  bundle: true,
  platform: 'browser',
  target: 'chrome138',
  write: false,
});
const runtime = await build({
  absWorkingDir: runtimeDir,
  entryPoints: ['browser-test/background-entry.ts'],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'chrome138',
  write: false,
  minify: true,
  plugins: [inlineCss],
});

const chapter = { id: 'chapter-1', name: 'Chapter one', path: '/one', progress: 0 };
const config = {
  documentId: 'background-browser-test',
  mode: 'reader',
  novel: { id: 'novel-1', name: 'Background test', path: '/novel' },
  chapter,
  nextChapter: null,
  chapters: [chapter],
  strings: { imageClose: 'Close image', tts: 'Read aloud' },
  autoSaveInterval: 1000,
  runtime: {
    editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
    menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
    loadingNext: false, noMoreChapters: false,
  },
  ui: { bionicEnabled: false, ttsEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
};
const paragraphs = '<p>A calm chapter under the stars. The background stays still while reading.</p>'.repeat(60);

const server = createServer((req, res) => {
  const name = new URL(req.url, 'http://localhost').pathname.slice(1);
  if (name === 'black.svg' || name === 'white.svg') {
    res.setHeader('Content-Type', 'image/svg+xml');
    res.end(`<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="${name.split('.')[0]}"/></svg>`);
  } else if (name === 'reader.css') {
    res.setHeader('Content-Type', 'text/css');
    res.end(css.outputFiles[0].text);
  } else if (name === 'reader.js') {
    res.setHeader('Content-Type', 'text/javascript');
    res.end(runtime.outputFiles[0].text);
  } else if (!name) {
    res.setHeader('Content-Type', 'text/html');
    res.end(`<html><head><link rel="stylesheet" href="reader.css"><style>
      :root { --reader-background-color:#fff; --reader-text-color:#123456;
      --reader-font-size:20px; --reader-line-height:1.6; --reader-font-family:serif;
      --reader-margin-top:20px; --reader-margin-bottom:20px; --reader-margin-left:20px;
      --reader-margin-right:20px; --reader-paragraph-spacing:1em; --reader-paragraph-indent:0; }
      </style><script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
      <script defer src="reader.js"></script></head><body><main id="nekori-reader" class="nekori-reader" data-nekori-reader-root>
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="chapter-1" data-nekori-chapter-name="Chapter one" data-nekori-chapter-path="/one">${paragraphs}</nekori-chapter>
      </main></body></html>`);
  } else {
    res.writeHead(404).end();
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
  await page.waitForFunction(() => typeof window.applyReaderStyle === 'function');
  const config = { image: `${origin}/black.svg`, size: 'cover', position: 'center', opacity: 100, blur: 0 };
  let appliedCount = 0;
  const apply = async value => {
    const previous = appliedCount++;
    await page.evaluate(background => window.applyReaderStyle({ css: '', bodyClasses: '', background }), value);
    await page.waitForFunction(previous => window.readerMessages.filter(message => message.kind === 'result').length > previous, previous);
    const result = await page.evaluate(() => window.readerMessages.filter(message => message.kind === 'result').at(-1));
    assert.equal(result.error, undefined, result.error?.message);
  };
  const color = () => page.evaluate(() => getComputedStyle(document.body).color);
  const waitColor = expected => page.waitForFunction(expected => getComputedStyle(document.body).color === expected, expected);
  const layer = '#nekori-reader-background';
  const root = '#nekori-reader';
  const chapterText = 'nekori-chapter p';
  await apply(config);
  await page.waitForFunction(selector => getComputedStyle(document.querySelector(selector)).backgroundImage !== 'none', layer);
  assert.equal(await color(), 'rgb(18, 52, 86)');
  assert.equal(await page.locator(`${root} ${chapterText}`).count(), 60);
  assert.equal(await page.locator(layer).getAttribute('aria-hidden'), 'true');
  await page.evaluate(() => scrollTo(0, 500));
  assert.equal((await page.locator(layer).boundingBox()).y, 0);
  await apply({ ...config, opacity: 0 });
  await waitColor('rgb(18, 52, 86)');
  await apply({ ...config, image: `${origin}/white.svg` });
  await waitColor('rgb(18, 52, 86)');
  await apply({ ...config, blur: 5, repeat: true, position: 'top', size: 'auto' });
  await waitColor('rgb(18, 52, 86)');
  await page.waitForFunction(selector => getComputedStyle(document.querySelector(selector)).backgroundImage !== 'none', layer);
  const style = await page.locator(layer).evaluate(el => {
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
    const actual = await page.locator(layer).evaluate(el => {
      const css = getComputedStyle(el);
      return [css.backgroundSize, css.backgroundRepeat];
    });
    assert.deepEqual(actual, [expectedSize, expectedRepeat]);
  }
  await apply({ ...config, image: `${origin}/white.svg` });
  await page.waitForFunction(selector => getComputedStyle(document.querySelector(selector)).backgroundImage !== 'none', layer);
  // System/status-bar reserves belong to prose margins, not the background viewport.
  await page.evaluate(() => {
    document.documentElement.style.setProperty('--reader-margin-top', '64px');
    document.documentElement.style.setProperty('--reader-margin-bottom', '72px');
    document.documentElement.style.setProperty('--reader-margin-left', '48px');
    scrollTo(0, 0);
  });
  assert.deepEqual(await page.locator(layer).boundingBox(), { x: 0, y: 0, width: 412, height: 820 });
  assert.equal(await page.locator('body').evaluate(el => getComputedStyle(el).paddingTop), '64px');
  // Fixed decoration must not disturb actual paged columns or discard paragraphs.
  await page.evaluate(() => {
    scrollTo(0, 0);
    const reader = document.querySelector('#nekori-reader');
    reader.classList.add('is-paged');
    reader.dataset.readerSpread = 'single';
  });
  assert.equal(await page.locator(`${root} ${chapterText}`).count(), 60);
  assert.deepEqual(await page.locator(root).evaluate(el => {
    const css = getComputedStyle(el);
    return [css.paddingTop, css.paddingBottom, css.paddingLeft, css.columnCount];
  }), ['64px', '72px', '48px', '1']);
  const first = await page.locator(layer).boundingBox();
  await page.locator(root).evaluate(el => { el.scrollLeft = 400; });
  assert.deepEqual(await page.locator(layer).boundingBox(), first);
  if (process.env.BACKGROUND_SCREENSHOT) await page.screenshot({ path: process.env.BACKGROUND_SCREENSHOT });
  await apply({ ...config, image: '' });
  await waitColor('rgb(18, 52, 86)');
  assert.equal(await page.locator(layer).evaluate(el => getComputedStyle(el).backgroundImage), 'none');
  await apply({ ...config, image: `${origin}/missing.png`, errorMessage: 'Background unavailable' });
  await page.waitForFunction(() => window.readerMessages.some(message =>
    message.kind === 'event' && message.method === 'reader.error'));
  assert.deepEqual(await page.evaluate(() => window.readerMessages
    .filter(message => message.kind === 'event' && message.method === 'reader.error')
    .map(message => message.payload.message)), ['Background unavailable']);
  assert.equal(await color(), 'rgb(18, 52, 86)');
  // An older image request cannot override a newer selection or a disabled background.
  await page.evaluate(url => {
    window.applyReaderStyle({ css: '', bodyClasses: '', background: { image: url } });
    window.applyReaderStyle({ css: '', bodyClasses: '', background: { image: '' } });
  }, `${origin}/black.svg`);
  await page.waitForTimeout(100);
  assert.equal(await color(), 'rgb(18, 52, 86)');
  assert.deepEqual(errors, []);
  await page.evaluate(() => window.disposeReader());
  console.log('Reader backgrounds: preserved text colors, opacity, manual colors, blur/repeat, scrolling, columns, reset, errors and stale loads passed.');
} finally {
  server.closeAllConnections();
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
