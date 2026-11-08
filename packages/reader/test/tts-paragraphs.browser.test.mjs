import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const rules = [
  { pattern: String.raw`[\u0009-\u000D\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+`, replacement: ' ' },
  { pattern: String.raw`^[ "'“”‘’]+|[ "'“”‘’]+$`, replacement: '' },
  { pattern: String.raw`\.{2,}`, replacement: '…' },
  { pattern: String.raw`(?![%$+/@＠&=°§·…×÷<>≤≥≈≠±‰€£¥₹₩¢。、！？；：「」『』（）【】，．･＋／＝％＆＜＞￥＄〜～√⁄⋅‱′″∞.,!?;:'"“”‘’‐‑‒–—―−⁓⸺⸻﹘﹣－-])[\p{S}\p{P}\p{Cc}\p{Cs}]`, replacement: ' ' },
  { pattern: ' +', replacement: ' ' },
  { pattern: String.raw`^[ "'“”‘’]+|[ "'“”‘’]+$`, replacement: '' },
  { pattern: String.raw`^(?:[‐‑‒–—―−⁓⸺⸻﹘﹣－-] *){3,}$`, replacement: '' },
  { pattern: String.raw`^[\p{M}\p{Cf} ]+$`, replacement: '' },
];

async function bundleTtsCommands() {
  const result = await build({
    absWorkingDir: packageDir,
    stdin: {
      contents: `
        import { registerTtsDomCommands } from './src/tts/highlight.ts';
        import { readableBlockAtPoint } from './src/tts/paragraphs.ts';
        window.findTtsBlockAtPoint = readableBlockAtPoint;
        window.makeTtsHarness = (root, config) => {
          const commands = new Map();
          const host = { root, config, onCommand(name, handler) { commands.set(name, handler); return () => {}; } };
          registerTtsDomCommands(host, null);
          return commands;
        };
      `,
      resolveDir: packageDir,
      sourcefile: 'tts-test-entry.ts',
      loader: 'ts',
    },
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'chrome138',
    write: false,
  });
  return result.outputFiles[0].text;
}

browserTest(test, 'TTS text, visible position, highlight, hover target, and consumed marker share normalized leaf blocks', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 480, height: 700 } });
    await page.setContent(`<main id="reader"><nekori-chapter data-nekori-chapter-id="1">
      <div id="nekori-title-novel">Internal reader title.</div>
      <div id="div-leaf">Direct block <strong>with inline text</strong>.</div>
      <blockquote><p id="quote">Quoted passage.</p><ul><li id="list-item">List <em>item</em>.</li></ul></blockquote>
      <p id="symbol-only">★★★</p><p id="emoji-only">👩‍🚀</p>
      <div id="inline-block"><span><b>Nested inline</b></span> <a>link</a></div>
      <nekori-chapter-summary></nekori-chapter-summary>
    </nekori-chapter></main>`);
    await page.locator('nekori-chapter-summary').evaluate((host) => {
      const shadow = host.attachShadow({ mode: 'open' });
      shadow.innerHTML = '<p>Summary must stay outside TTS.</p>';
    });
    await page.addScriptTag({ content: await bundleTtsCommands() });
    const result = await page.evaluate((normalizationRules) => {
      const root = document.querySelector('#reader');
      const commands = window.makeTtsHarness(root, { ttsNormalizationRules: normalizationRules });
      const run = (name, payload = {}) => commands.get(name)(payload, new AbortController().signal);
      const text = run('tts.extractText', { chapterId: '1' });
      const paragraphs = text.split('\n');
      run('tts.highlight', { chapterId: '1', paragraphIndex: 2, backgroundColor: '#ffff00', textColor: '#000000' });
      const highlighted = document.querySelector('#list-item').classList.contains('nekori-tts-highlight');
      document.querySelector('#div-leaf').style.cssText = 'position:absolute;top:-100px';
      const visible = run('tts.firstVisibleParagraph', { chapterId: '1' });
      const inlineTarget = document.querySelector('#list-item em');
      inlineTarget.setAttribute('data-nekori-tts-target', '1');
      const tagged = run('tts.taggedParagraph', { chapterId: '1' });
      const taggedAgain = run('tts.taggedParagraph', { chapterId: '1' });
      const markerConsumed = !inlineTarget.hasAttribute('data-nekori-tts-target');
      const listRect = document.querySelector('#list-item em').getBoundingClientRect();
      const hoverBlock = window.findTtsBlockAtPoint(root, listRect.x + 2, listRect.y + 2, normalizationRules);
      return { text, paragraphs, highlighted, visible, tagged, taggedAgain, markerConsumed, hoverBlock: hoverBlock?.id };
    }, rules);

    assert.deepEqual(result, {
      text: 'Direct block with inline text.\nQuoted passage.\nList item.\nNested inline link',
      paragraphs: ['Direct block with inline text.', 'Quoted passage.', 'List item.', 'Nested inline link'],
      highlighted: true,
      visible: 1,
      tagged: 2,
      taggedAgain: -1,
      markerConsumed: true,
      hoverBlock: 'list-item',
    });
    await page.close();
  } finally {
    await browser.close();
  }
});
