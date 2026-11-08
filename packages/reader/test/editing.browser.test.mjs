import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

async function mount(browser, markup, mode = 'reader') {
  const page = await browser.newPage();
  await page.setContent(`<main id="nekori-reader">${markup}</main>`);
  const bundle = await build({
    stdin: {
      contents: "import { registerEditingCommands } from './src/interactions/editing.ts'; window.installEditTest = registerEditingCommands;",
      resolveDir: path.resolve(fileURLToPath(new URL('..', import.meta.url))),
    },
    bundle: true, format: 'iife', platform: 'browser', target: 'chrome138', write: false,
  });
  await page.addScriptTag({ content: bundle.outputFiles[0].text });
  await page.evaluate((documentMode) => {
    const root = document.getElementById('nekori-reader');
    const commands = new Map();
    const events = [];
    const config = { mode: documentMode, chapters: [{ id: '9223372036854775807' }, { id: '-2' }] };
    const host = {
      root, config, chapter: config.chapters[0],
      onCommand(name, handler) { commands.set(name, handler); return () => commands.delete(name); },
      emit(name, payload) { events.push({ name, payload }); },
    };
    window.editTest = { commands, events, config, disposers: window.installEditTest(host) };
  }, mode);
  return page;
}

const chapter = '<nekori-chapter data-nekori-chapter-id="9223372036854775807"><p>Original</p></nekori-chapter>';

browserTest(test, 'edit refuses error loading and wrapperless reader documents without root fallback', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    for (const [mode, markup] of [['error', chapter], ['loading', chapter], ['reader', '<p>Error placeholder</p>']]) {
      const page = await mount(browser, markup, mode);
      try {
        const actual = await page.evaluate(() => {
          const accepted = window.editTest.commands.get('edit.enter')({});
          window.editTest.commands.get('edit.exit')({});
          return { accepted, editable: document.querySelectorAll('[contenteditable]').length,
            saves: window.editTest.events.filter(event => event.name === 'edit.save').length };
        });
        assert.deepEqual(actual, { accepted: false, editable: 0, saves: 0 }, mode);
      } finally { await page.close(); }
    }
  } finally { await browser.close(); }
});

browserTest(test, 'edit saves only chapter wrappers preserving decimal-string ids and HTML', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  const page = await mount(browser, `${chapter}<aside>Reader UI</aside><nekori-chapter data-nekori-chapter-id="-2"><p>Plain text</p></nekori-chapter>`);
  try {
    assert.equal(await page.evaluate(() => window.editTest.commands.get('edit.enter')({})), true);
    await page.locator('nekori-chapter').first().evaluate(element => { element.innerHTML = '<p><b>Edited</b></p>'; });
    const result = await page.evaluate(() => {
      window.editTest.commands.get('edit.exit')({});
      return { saved: JSON.parse(window.editTest.events.find(event => event.name === 'edit.save').payload.content),
        editable: document.querySelectorAll('[contenteditable]').length };
    });
    assert.deepEqual(result, { saved: [
      { id: '9223372036854775807', content: '<p><b>Edited</b></p>' },
      { id: '-2', content: '<p>Plain text</p>' },
    ], editable: 0 });
  } finally { await page.close(); await browser.close(); }
});

browserTest(test, 'discard restores the first edit snapshot and never replaces a new error root', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  const page = await mount(browser, chapter);
  try {
    await page.evaluate(() => {
      window.editTest.commands.get('edit.enter')({});
      document.querySelector('nekori-chapter').textContent = 'Edited';
      window.editTest.commands.get('edit.enter')({});
      window.editTest.commands.get('edit.discard')({});
    });
    assert.equal(await page.locator('nekori-chapter').innerHTML(), '<p>Original</p>');
    await page.evaluate(() => {
      window.editTest.commands.get('edit.enter')({});
      window.editTest.config.mode = 'error';
      document.getElementById('nekori-reader').innerHTML = '<p>New error</p>';
      window.editTest.commands.get('edit.exit')({});
      window.editTest.commands.get('edit.discard')({});
    });
    assert.equal(await page.locator('#nekori-reader').innerHTML(), '<p>New error</p>');
    assert.equal(await page.evaluate(() => window.editTest.events.filter(event => event.name === 'edit.save').length), 0);
  } finally { await page.close(); await browser.close(); }
});
