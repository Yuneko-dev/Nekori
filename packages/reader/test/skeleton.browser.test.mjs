import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

browserTest(test, 'loading skeleton keeps native margins, colors and reduced-motion behavior', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 412, height: 820 } });
    const config = {
      background: '#121212', base: 'hsl(0, 0%, 7.8%)', highlight: 'hsl(0, 0%, 9.9%)',
      fontSize: 18, lineGap: 10.8, lineHeight: 28.8,
      marginTop: 48, marginRight: 24, marginBottom: 16, marginLeft: 20,
    };
    const css = await readFile(new URL('../src/styles/skeleton.css', import.meta.url), 'utf8');
    await page.setContent(`<!doctype html><style>${css}</style><div role="status" class="status">Loading &lt;chapter&gt;</div><main id="skeleton" class="skeleton" aria-hidden="true"></main><script id="nekori-skeleton-config" type="application/json">${JSON.stringify(config)}</script>`);
    const result = await build({
      entryPoints: [fileURLToPath(new URL('../src/entries/skeleton.ts', import.meta.url))],
      bundle: true, format: 'iife', target: 'chrome138', write: false,
    });
    await page.addScriptTag({ content: result.outputFiles[0].text });
    assert.ok(await page.locator('#skeleton .line').count() > 0);
    assert.equal(await page.locator('[role=status]').textContent(), 'Loading <chapter>');
    const actual = await page.evaluate(() => {
      const body = getComputedStyle(document.body);
      const line = getComputedStyle(document.querySelector('.line'));
      return { padding: body.padding, background: body.backgroundColor, height: line.height, gap: line.marginBottom,
        animation: getComputedStyle(document.querySelector('.shimmer')).animationName };
    });
    assert.deepEqual(actual, { padding: '48px 24px 16px 20px', background: 'rgb(18, 18, 18)', height: '18px', gap: '10.8px', animation: 'shimmer' });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    assert.equal(await page.locator('.shimmer').first().evaluate((element) => getComputedStyle(element).animationName), 'none');
  } finally {
    await browser.close();
  }
});
