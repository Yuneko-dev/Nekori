import assert from 'node:assert/strict';
import { build } from 'esbuild';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

browserTest(test, 'programmatic restored scroll reports geometry separately from snapped progress', async () => {
  const entry = await build({
    stdin: { contents: `import { createScrollTracking } from './src/layout/scroll-tracking.ts';
      window.events=[];
      window.tracking=createScrollTracking({root:document.querySelector('main'),host:{
        emit:(name,payload)=>window.events.push({name,payload})
      }});
      window.tracking.configure({infiniteScrollEnabled:false,loadThreshold:.8,doneThreshold:.95});`,
    resolveDir: fileURLToPath(new URL('..', import.meta.url)) },
    bundle: true, format: 'iife', platform: 'browser', target: 'chrome138', write: false,
  });
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 400, height: 600 } });
    await page.setContent('<style>body{margin:0}main{height:20000px}</style><main>Long chapter</main>');
    await page.addScriptTag({ content: entry.outputFiles[0].text });
    await page.evaluate(() => {
      window.scrollTo(0, (document.documentElement.scrollHeight - innerHeight) * .999);
      window.tracking.resetChapterTracking();
    });
    await page.waitForFunction(() => window.events.some(event => event.name === 'scroll.progressPersist'));
    const restored = await page.evaluate(() => window.events.filter(event => event.name === 'scroll.progressPersist').at(-1));
    assert.equal(restored.payload.progress, 1);
    assert.equal(restored.payload.atEnd, false, 'a snapped threshold is not the geometric end');
    await page.evaluate(() => {
      window.events.length = 0;
      window.scrollTo(0, document.documentElement.scrollHeight);
    });
    await page.waitForFunction(() => window.events.some(event => event.name === 'scroll.progressPersist' && event.payload.atEnd));
    assert.equal(await page.evaluate(() => window.events.filter(event => event.name === 'scroll.progressPersist').at(-1).payload.atEnd), true);
  } finally {
    await browser.close();
  }
});
