import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));

function inlineCssPlugin() {
  return {
    name: 'runtime-bootstrap-inline-css',
    setup(builder) {
      builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
        path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
        namespace: 'runtime-bootstrap-css',
      }));
      builder.onLoad({ filter: /.*/, namespace: 'runtime-bootstrap-css' }, async (args) => ({
        contents: await readFile(args.path, 'utf8'),
        loader: 'text',
      }));
    },
  };
}

async function bundleEntry() {
  const result = await build({
    absWorkingDir: packageDir,
    entryPoints: ['src/entries/reader.ts'],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'chrome138',
    write: false,
    plugins: [inlineCssPlugin()],
  });
  return result.outputFiles[0].text;
}

browserTest(test, 'fresh reader documents restore saved background and UI preferences without live commands', async () => {
  const bundle = await bundleEntry();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><path fill="red" d="M0 0h2v2H0z"/></svg>');
    const config = {
      documentId: 'background-boot', mode: 'reader',
      novel: { id: 'n', name: 'Novel', path: '/novel' },
      chapter: { id: '1', name: 'Chapter', path: '/one', progress: 0 },
      nextChapter: null, chapters: [], strings: {}, autoSaveInterval: 5000,
      runtime: {
        editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
        menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
        loadingNext: false, noMoreChapters: false,
      },
      style: { background: { image, opacity: 45, blur: 2, size: 'contain', position: 'bottom', repeat: true } },
      ui: { ttsEnabled: false, bionicEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
    };
    // Each new page has a fresh runtime, as when leaving and reopening the reader.
    for (let opening = 0; opening < 2; opening++) {
      const page = await browser.newPage();
      await page.setContent(`<script>window.__nekoriNativeTransport={onmessage:null,postMessage(){}};</script>
        <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
        <main data-nekori-reader-root><nekori-chapter data-nekori-chapter-id="1"><p>Text</p></nekori-chapter></main>`);
      await page.addScriptTag({ content: bundle });
      await page.waitForFunction(() => window.nekori !== undefined);
      await page.evaluate(() => window.nekori.ready);
      // Wait for the real image decode too; ready does not wait for decorative assets.
      await page.waitForFunction(() => document.querySelector('#nekori-reader-background')?.style.backgroundImage.includes('data:image/svg+xml'), null, { timeout: 3000 });
      const { image: loadedImage, ...settings } = await page.locator('#nekori-reader-background').evaluate(layer => ({
        image: layer.style.backgroundImage,
        opacity: layer.style.opacity,
        blur: layer.style.filter,
        size: layer.style.backgroundSize,
        position: layer.style.backgroundPosition,
        repeat: layer.style.backgroundRepeat,
      }));
      assert.ok(loadedImage.includes(image));
      assert.deepEqual(settings, {
        opacity: '0.45', blur: 'blur(2px)', size: 'contain', position: 'center bottom', repeat: 'repeat',
      });
      assert.equal(await page.locator('#nekori-tts-controller').evaluate(el => el.classList.contains('hidden')), true);
      assert.ok(await page.locator('nekori-chapter b[data-nekori-bionic]').count() > 0);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

browserTest(test, 'head bootstrap initializes plain-text chapters before ready and source plugin scripts', async () => {
  const bundle = await bundleEntry();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    const literal = '<img src=x onerror="window.injected = true">';
    const config = {
      documentId: 'head-bootstrap', mode: 'reader',
      novel: { id: 'novel-id', name: 'Novel', path: '/novel' },
      chapter: { id: 'chapter-id', name: 'Chapter', path: '//opaque/#path', progress: 0, number: 1 },
      nextChapter: null, chapters: [], strings: {}, autoSaveInterval: 5000,
      runtime: {
        editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
        menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
        loadingNext: false, noMoreChapters: false,
      },
      layout: { enabled: true, spread: 'single', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: 'chapter-id' },
      initialContent: { kind: 'plainText', text: `First paragraph ${'reader line '.repeat(60)}\n\n${literal}\n\nThird paragraph ${'reader line '.repeat(60)}` },
      pluginJavaScript: `window.pluginObservation = {
        readyBeforePlugin: window.__nativeMessages.some(({message}) => message.kind === 'ready'),
        paragraphCount: document.querySelectorAll('#reader nekori-chapter p').length,
        literalText: document.querySelector('#reader nekori-chapter').textContent.includes(${JSON.stringify(literal)}),
        imageCount: document.querySelectorAll('#reader nekori-chapter img').length,
        opaquePath: document.querySelector('#reader nekori-chapter').getAttribute('data-nekori-chapter-path')
      };`,
    };
    await page.route('**/reader-runtime.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: bundle }));
    await page.setContent(`<!doctype html><html><head>
      <script>window.__nativeMessages=[]; window.__nativeReady=false; window.__nekoriNativeTransport={onmessage:null,postMessage(raw){const message=JSON.parse(raw);window.__nativeMessages.push({message,readyAtSend:window.__nativeReady});if(message.kind==='ready')window.__nativeReady=true;}};</script>
      <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
      <script src="https://reader.test/reader-runtime.js"></script>
      </head><body><main id="reader" data-nekori-reader-root>
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="chapter-id" data-nekori-chapter-name="Chapter" data-nekori-chapter-path="//opaque/#path"></nekori-chapter>
      <script>window.sourceScriptRan = true;</script>
      </main></body></html>`);
    await page.waitForFunction(() => window.pluginObservation !== undefined);
    // Layout publishes measured position on animation frames after controller readiness.
    await page.waitForFunction(() => window.__nativeMessages.some(({ message }) => message.method === 'layout.positionChanged'));
    const actual = await page.evaluate(() => ({
      observation: window.pluginObservation,
      ready: window.__nativeMessages.some(({message}) => message.kind === 'ready'),
      apiReady: Boolean(window.nekori?.layout),
      sourceScriptRan: window.sourceScriptRan,
      injected: window.injected === true,
      transport: window.__nativeMessages,
    }));
    assert.deepEqual(actual.observation, {
      readyBeforePlugin: true,
      paragraphCount: 3,
      literalText: true,
      imageCount: 0,
      opaquePath: '//opaque/#path',
    });
    assert.equal(actual.ready, true);
    assert.equal(actual.apiReady, true);
    assert.equal(actual.sourceScriptRan, true);
    assert.equal(actual.injected, false);
    assert.deepEqual(actual.transport.slice(0, 3).map(({ message }) => [message.kind, message.method]), [
      ['boot', undefined],
      ['ready', undefined],
      ['event', 'gesture.ready'],
    ]);
    assert.equal(actual.transport.filter(({ message }) => message.kind === 'event').every(({ readyAtSend }) => readyAtSend), true);
    assert.equal(actual.transport.some(({ message }) => message.method === 'layout.positionChanged'), true);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'disposing a head bootstrap cancels deferred DOM initialization and readiness', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    const config = {
      documentId: 'disposed-head', mode: 'reader',
      novel: { id: 'n', name: 'Novel', path: '/novel' },
      chapter: { id: '1', name: 'Chapter', path: '/chapter', progress: 0 },
      nextChapter: null, chapters: [], strings: {}, autoSaveInterval: 5000,
      runtime: { editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
        menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false, loadingNext: false, noMoreChapters: false },
      pluginJavaScript: 'window.shouldNotExecute = true',
    };
    await page.route('**/reader-runtime.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: '' }));
    const source = await bundleEntry();
    await page.setContent(`<!doctype html><head>
      <script>window.messages=[];window.__nekoriNativeTransport={onmessage:null,postMessage(raw){messages.push(JSON.parse(raw))}}</script>
      <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
      <script>${source}</script>
      <script>const reader=window.nekori;reader.ready.catch(error=>window.cancelledReadiness=error.name);reader.dispose();</script>
      </head><body><main id="nekori-reader" data-nekori-reader-root><nekori-chapter data-nekori-chapter-id="1"><p>Text</p></nekori-chapter></main></body>`);
    await page.waitForFunction(() => window.cancelledReadiness !== undefined);
    assert.deepEqual(await page.evaluate(() => ({
      cancelled: window.cancelledReadiness, api: typeof window.nekori,
      plugin: window.shouldNotExecute === true,
      widgets: document.querySelectorAll('#nekori-tts-controller,#nekori-image-modal').length,
      messages: window.messages.map(({ kind }) => kind),
    })), { cancelled: 'AbortError', api: 'undefined', plugin: false, widgets: 0, messages: ['boot'] });
  } finally {
    await browser.close();
  }
});
