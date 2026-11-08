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
    name: 'runtime-startup-failure-inline-css',
    setup(builder) {
      builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
        path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
        namespace: 'runtime-startup-failure-css',
      }));
      builder.onLoad({ filter: /.*/, namespace: 'runtime-startup-failure-css' }, async (args) => ({
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

browserTest(test, 'production entry reports invalid layout and direct-video configs without creating the reader API', async () => {
  const bundle = await bundleEntry();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  const invalidConfigs = [
    {
      name: 'layout',
      config: createConfig({ layout: { enabled: true, spread: 'many' } }),
    },
    {
      name: 'direct video',
      config: createConfig({ player: { mode: 'playback', video: { path: 'opaque-path', progress: 0, direct: true, type: 'm3u8' } } }),
    },
  ];
  try {
    for (const { name, config } of invalidConfigs) {
      const page = await browser.newPage();
      const documentId = config.documentId;
      await page.setContent(`<!doctype html><html><head>
        <script>
          window.__nativeMessages = [];
          window.__nekoriNativeTransport = { onmessage: null, postMessage(raw) { window.__nativeMessages.push(JSON.parse(raw)); } };
          window.addEventListener('error', (event) => { window.__startupThrown = event.error?.message ?? event.message; });
        </script>
        <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
        </head><body><main id="reader" data-nekori-reader-root></main></body></html>`);
      await page.addScriptTag({ content: bundle });
      await page.waitForFunction(() => window.__nativeMessages.length === 2);
      const result = await page.evaluate(() => ({
        messages: window.__nativeMessages,
        apiCreated: 'nekori' in window,
        startupThrown: window.__startupThrown,
      }));
      assert.equal(result.apiCreated, false, `${name} failure must not install a placeholder API`);
      assert.equal(typeof result.startupThrown, 'string', `${name} failure must still throw from production bootstrap`);
      assert.deepEqual(result.messages, [
        { protocolVersion: 1, documentId, kind: 'boot' },
        {
          protocolVersion: 1,
          documentId,
          kind: 'event',
          method: 'reader.error',
          payload: { message: 'The reader could not start because its configuration is invalid. Close and reopen the reader.' },
        },
      ]);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

browserTest(test, 'production entry does not send a native startup message when config identity cannot be recovered', async () => {
  const bundle = await bundleEntry();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent(`<!doctype html><html><head>
      <script>window.__nativeMessages=[];window.__nekoriNativeTransport={onmessage:null,postMessage(raw){window.__nativeMessages.push(JSON.parse(raw));}};</script>
      <script id="nekori-reader-config" type="application/json">{not-json</script>
      </head><body></body></html>`);
    await page.addScriptTag({ content: bundle });
    const messages = await page.evaluate(() => window.__nativeMessages);
    assert.deepEqual(messages, []);
    await page.close();
  } finally {
    await browser.close();
  }
});

function createConfig(overrides = {}) {
  return {
    documentId: 'raw:nonce/reader-session-2026',
    mode: 'reader',
    novel: { id: 'novel-id', name: 'Private Novel', path: '/opaque-novel-path' },
    chapter: { id: 'chapter-id', name: 'Private Chapter', path: '/opaque-chapter-path', progress: 0 },
    nextChapter: null,
    chapters: [],
    strings: {},
    autoSaveInterval: 5000,
    runtime: {
      editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
      menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
      loadingNext: false, noMoreChapters: false,
    },
    ...overrides,
  };
}
