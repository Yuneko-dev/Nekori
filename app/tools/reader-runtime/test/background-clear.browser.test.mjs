import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

async function bundleStyles() {
  const result = await build({
    absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
    entryPoints: ['./browser-test/background-clear-entry.ts'],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'chrome138',
    write: false,
  });
  return result.outputFiles[0].text;
}

browserTest(test, 'style.apply clears a background and ignores an older pending image load', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent(`<!doctype html><html><head>
      <script id="nekori-reader-background-config" type="application/json">{"image":"","errorMessage":"failed"}</script>
    </head><body><main id="reader"></main></body></html>`);
    await page.addScriptTag({ content: await bundleStyles() });
    const result = await page.evaluate(() => {
      const pending = [];
      window.Image = class {
        set src(value) { this.url = value; pending.push(this); }
      };
      const handlers = new Map();
      const host = {
        root: document.querySelector('#reader'), config: {}, context: {}, strings: {}, chapter: {}, nextChapter: null,
        events: { subscribe() { return () => {}; } },
        emit() {}, request: async () => undefined, onDispose() { return () => {}; }, fetch() {}, fetchNative() {},
        boot() {}, start() {}, dispose() {},
        onCommand(name, handler) { handlers.set(name, handler); return () => handlers.delete(name); },
      };
      const styles = window.registerReaderStyles(host);
      const apply = handlers.get('style.apply');
      apply({ css: '', bodyClasses: '', background: { image: 'old.png' } }, new AbortController().signal);
      apply({ css: '', bodyClasses: '', background: { image: '' } }, new AbortController().signal);
      pending[0].onload();
      const layer = document.querySelector('#nekori-reader-background');
      const afterClear = layer.style.backgroundImage;
      apply({ css: '', bodyClasses: '', background: { image: 'new.png' } }, new AbortController().signal);
      pending[1].onload();
      const afterNewLoad = layer.style.backgroundImage;
      styles.dispose();
      return { afterClear, afterNewLoad };
    });
    assert.deepEqual(result, { afterClear: 'none', afterNewLoad: 'url("new.png")' });
    await page.close();
  } finally {
    await browser.close();
  }
});
