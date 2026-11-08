import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

async function bundleWidgets() {
  const result = await build({
    absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
    entryPoints: ['../browser-test/widgets-browser-entry.ts'],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'chrome138',
    write: false,
    plugins: [{
      name: 'reader-css-text',
      setup(buildApi) {
        buildApi.onLoad({ filter: /\.css$/ }, async (args) => ({
          contents: await import('node:fs/promises').then((fs) => fs.readFile(args.path, 'utf8')),
          loader: 'text',
        }));
      },
    }],
  });
  return result.outputFiles[0].text;
}

browserTest(test, 'gesture ownership is captured through shadow controls and blocks embedded controls', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent('<main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>Text</p></nekori-chapter></main><iframe></iframe>');
    await page.addScriptTag({ content: await bundleWidgets() });
    const actual = await page.evaluate(() => {
      const harness = window.makeReaderWidgets(document.querySelector('#reader'));
      const summary = document.querySelector('nekori-chapter').attachShadow({ mode: 'open' });
      const button = document.createElement('button');
      const body = document.createElement('div');
      summary.append(button, body);
      button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, isPrimary: true }));
      body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, isPrimary: true }));
      document.querySelector('iframe').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, isPrimary: true }));
      return harness.events.filter(([name]) => name === 'gesture.claim').map(([, payload]) => payload.owner);
    });
    assert.deepEqual(actual, ['blocked', 'surface', 'blocked']);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'summary output is text-only, isolated from paragraph indices, and focus reports absent cards', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent('<main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>Ordinary paragraph.</p></nekori-chapter></main>');
    await page.addScriptTag({ content: await bundleWidgets() });
    const actual = await page.evaluate(() => {
      const harness = window.makeReaderWidgets(document.querySelector('#reader'));
      const focusedBefore = harness.commands.get('summary.focus')({ chapterId: '1' });
      harness.commands.get('summary.render')({
        chapterId: '1', state: 'ready', text: '<img src=x onerror=alert(1)>\n\nSecond <b>literal</b>',
        labels: { title: 'Summary', loading: 'Loading', cancel: 'Cancel', regenerate: 'Again', close: 'Close' },
      });
      const host = document.querySelector('nekori-chapter-summary');
      const body = host.shadowRoot.querySelector('.body');
      return {
        focusedBefore,
        shadowMode: host.shadowRoot.mode,
        readerParagraphs: document.querySelector('nekori-chapter').querySelectorAll('p').length,
        summaryParagraphs: body.querySelectorAll('p').length,
        summaryImages: body.querySelectorAll('img').length,
        literal: body.textContent.includes('<img src=x onerror=alert(1)>'),
      };
    });
    assert.deepEqual(actual, {
      focusedBefore: false,
      shadowMode: 'open',
      readerParagraphs: 1,
      summaryParagraphs: 2,
      summaryImages: 0,
      literal: true,
    });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'TTS extracts ordered reader paragraphs and snippets preserve source bytes', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent('<main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>First</p><p>Second</p><button>Skip</button></nekori-chapter></main>');
    await page.addScriptTag({ content: await bundleWidgets() });
    const result = await page.evaluate(() => {
      const harness = window.makeReaderWidgets(document.querySelector('#reader'));
      const text = harness.commands.get('tts.extractText')({ chapterId: '1' });
      harness.commands.get('snippet.run')({ code: 'window.snippetSource = "  preserve\\nbytes  ";' });
      return { text, source: window.snippetSource };
    });
    // Native's existing leaf-block traversal also reads a source-authored button with inline text.
    assert.deepEqual(result, { text: 'First\nSecond\nSkip', source: '  preserve\nbytes  ' });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'downloaded video replay control dispatches a native action and tears down', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent('<main id="reader"><button id="nekori-play-local-video"><span>Play</span></button></main>');
    await page.addScriptTag({ content: await bundleWidgets() });
    await page.evaluate(() => { window.localHarness = window.makeReaderWidgets(document.querySelector('#reader')); });
    await page.locator('#nekori-play-local-video span').click();
    assert.equal(await page.evaluate(() => window.localHarness.events.filter(([name]) => name === 'media.playLocalVideo').length), 1);
    await page.evaluate(() => window.localHarness.dispose());
    await page.locator('#nekori-play-local-video span').click();
    assert.equal(await page.evaluate(() => window.localHarness.events.filter(([name]) => name === 'media.playLocalVideo').length), 1);
  } finally {
    await browser.close();
  }
});
