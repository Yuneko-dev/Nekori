import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const readerVariables = `:root {
  --reader-margin-top: 30px; --reader-margin-bottom: 20px;
  --reader-margin-left: 20px; --reader-margin-right: 20px;
  --reader-font-size: 20px; --reader-line-height: 1.6;
  --reader-paragraph-spacing: 12px; --reader-paragraph-indent: 0;
  --reader-text-color: #111; --reader-background-color: #fff;
}`;

function inlineCssPlugin() {
  return {
    name: 'curl-native-inline-css',
    setup(builder) {
      builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
        path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
        namespace: 'curl-inline-css',
      }));
      builder.onLoad({ filter: /.*/, namespace: 'curl-inline-css' }, async (args) => ({
        contents: await readFile(args.path, 'utf8'),
        loader: 'text',
      }));
    },
  };
}

async function bundleReader() {
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

async function readerCss() {
  const result = await build({
    absWorkingDir: packageDir,
    entryPoints: ['src/styles/reader.css'],
    bundle: true,
    platform: 'browser',
    target: 'chrome138',
    write: false,
  });
  return result.outputFiles[0].text;
}

function readerConfig(direction, spread) {
  const chapter = { id: 'curl-chapter', name: 'Curl chapter', path: '/curl', progress: 0 };
  return {
    documentId: `curl-${direction}-${spread}`,
    mode: 'reader',
    novel: { id: 'curl-novel', name: 'Curl novel', path: '/novel' },
    chapter,
    nextChapter: null,
    chapters: [chapter],
    strings: { tts: 'Read aloud', imageClose: 'Close image' },
    autoSaveInterval: 1000,
    layout: {
      enabled: true, direction, spread, vertical: false, infinite: false,
      threshold: 0.8, chapterId: chapter.id,
    },
    runtime: {
      editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
      menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
      loadingNext: false, noMoreChapters: false,
    },
    ui: { bionicEnabled: false, ttsEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
  };
}

async function nativeCommand(page, requestId, method, payload = {}) {
  await page.evaluate(({ requestId, method, payload }) => {
    window.__deliverReaderCommand({
      protocolVersion: 1,
      documentId: window.__readerDocumentId,
      kind: 'command',
      requestId,
      method,
      payload,
    });
  }, { requestId, method, payload });
  await page.waitForFunction((id) => window.__readerMessages.some(
    (message) => message.kind === 'result' && message.requestId === id,
  ), requestId);
  return page.evaluate((id) => window.__readerMessages.find((message) => message.requestId === id), requestId);
}

browserTest(test, 'native curl commands preview, commit once, rollback silently, and recover after a boundary', async (t) => {
  const [source, css] = await Promise.all([bundleReader(), readerCss()]);
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    for (const direction of ['ltr', 'rtl']) {
      for (const spread of ['single', 'double']) {
        await t.test(`${direction} ${spread}`, async () => {
          const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
          const config = readerConfig(direction, spread);
          const paragraphs = Array.from({ length: 100 }, (_, index) =>
            `<p>Paragraph ${index}: ${'A long paragraph for measuring reader columns and page turns. '.repeat(6)}</p>`,
          ).join('');
          await page.setContent(`<!doctype html><html><head><style>${css}\n${readerVariables}</style>
            <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
            <script>
              window.__readerMessages = [];
              window.__readerDocumentId = ${JSON.stringify(config.documentId)};
              window.__nekoriNativeTransport = {
                onmessage: null,
                postMessage(raw) { window.__readerMessages.push(JSON.parse(raw)); },
              };
              window.__deliverReaderCommand = (message) => window.__nekoriNativeTransport.onmessage?.({ data: message });
            </script>
          </head><body><main id="nekori-reader" data-nekori-reader-root class="nekori-reader">
            <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="curl-chapter">${paragraphs}</nekori-chapter>
          </main><div id="reader-ui"></div></body></html>`);
          await page.addScriptTag({ content: source });
          await page.waitForFunction(() => window.__readerMessages.some((message) => message.kind === 'ready'));
          await page.waitForFunction(() => (window.nekori?.layout?.position?.unitCount ?? 0) > 3);
          await page.evaluate(() => { window.__readerMessages.length = 0; });

          const start = await page.evaluate(() => ({
            offset: document.querySelector('#nekori-reader').scrollLeft,
            unit: window.nekori.layout.position.unitIndex,
          }));
          const preview = await nativeCommand(page, 'preview', 'layout.prepareTurn', { delta: 1 });
          assert.equal(preview.payload, true);
          const previewState = await page.evaluate(() => ({
            offset: document.querySelector('#nekori-reader').scrollLeft,
            unit: window.nekori.layout.position.unitIndex,
            persistentEvents: window.__readerMessages.filter((message) =>
              message.kind === 'event' && message.method === 'scroll.progressPersist').length,
          }));
          assert.ok(Math.abs(previewState.offset - start.offset) >= 380,
            `native prepareTurn did not move a physical page: ${JSON.stringify({ start, previewState })}`);
          assert.equal(previewState.persistentEvents, 0, 'silent preview must not persist progress');

          const committed = await nativeCommand(page, 'commit', 'layout.finishTurn', { commit: true });
          assert.equal(committed.payload, true);
          await page.waitForTimeout(220);
          if (direction === 'ltr' && spread === 'single') {
            await page.waitForTimeout(450);
            await page.locator('#nekori-reader').evaluate((root) => root.dispatchEvent(new Event('scroll')));
            await page.waitForTimeout(220);
          }
          const committedState = await page.evaluate(() => ({
            offset: document.querySelector('#nekori-reader').scrollLeft,
            persists: window.__readerMessages.filter((message) =>
              message.kind === 'event' && message.method === 'scroll.progressPersist'),
          }));
          assert.ok(Math.abs(committedState.offset - start.offset) >= 380);
          assert.equal(committedState.persists.length, 1, 'committing a curl persists the destination once');

          await page.evaluate(() => { window.__readerMessages.length = 0; });
          const rollbackStart = await page.locator('#nekori-reader').evaluate((root) => root.scrollLeft);
          assert.equal((await nativeCommand(page, 'rollback-preview', 'layout.prepareTurn', { delta: -1 })).payload, true);
          const rollbackPreview = await page.locator('#nekori-reader').evaluate((root) => root.scrollLeft);
          assert.ok(Math.abs(rollbackPreview - rollbackStart) >= 380, 'rollback fixture must preview the prior page');
          assert.equal((await nativeCommand(page, 'rollback', 'layout.finishTurn', { commit: false })).payload, true);
          const rollbackState = await page.evaluate(() => ({
            offset: document.querySelector('#nekori-reader').scrollLeft,
            persists: window.__readerMessages.filter((message) =>
              message.kind === 'event' && message.method === 'scroll.progressPersist').length,
          }));
          assert.ok(Math.abs(rollbackState.offset - rollbackStart) < 1, 'rollback restores the committed page');
          assert.equal(rollbackState.persists, 0, 'rollback must not persist progress');

          await nativeCommand(page, 'seek-end', 'layout.seekPercent', { percent: 100 });
          await page.evaluate(() => { window.__readerMessages.length = 0; });
          const endOffset = await page.locator('#nekori-reader').evaluate((root) => root.scrollLeft);
          const boundary = await nativeCommand(page, 'boundary', 'layout.prepareTurn', { delta: 1 });
          assert.equal(boundary.payload, false, 'curl beyond the final unit is rejected');
          await page.evaluate(() => { window.__readerMessages.length = 0; });
          assert.equal((await nativeCommand(page, 'after-boundary', 'layout.move', { delta: -1 })).payload, true);
          await page.waitForFunction(() => window.__readerMessages.some((message) =>
            message.kind === 'event' && message.method === 'scroll.progressPersist'));
          const recovered = await page.evaluate(() => ({
            offset: document.querySelector('#nekori-reader').scrollLeft,
            persists: window.__readerMessages.filter((message) =>
              message.kind === 'event' && message.method === 'scroll.progressPersist').length,
          }));
          assert.ok(Math.abs(recovered.offset - endOffset) >= 380,
            'ordinary movement after a failed boundary advances from the final page');
          assert.equal(recovered.persists, 1, 'failed boundary must leave the layout unsilenced');
          await page.close();
        });
      }
    }
  } finally {
    await browser.close();
  }
});
