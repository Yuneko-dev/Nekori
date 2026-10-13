import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const readerControlsCss = readFile(new URL('../src/styles/reader-controls.css', import.meta.url), 'utf8');
const readerContentCss = readFile(new URL('../src/styles/reader-content.css', import.meta.url), 'utf8');

async function bundleTts() {
  const result = await build({
    absWorkingDir: packageDir,
    stdin: {
      contents: `
        import { createTtsController } from './src/tts/controller.ts';
        import { registerTtsDomCommands } from './src/tts/highlight.ts';
        window.installTts = (root, config, layout = null) => {
          const events = [];
          const host = {
            root, config,
            emit(name, payload) { events.push({ name, payload }); },
            onCommand(name, handler) { commands.set(name, handler); return () => {}; },
          };
          const commands = new Map();
          const controller = createTtsController(root, host, config);
          registerTtsDomCommands(host, layout);
          return { controller, events, commands };
        };
      `,
      resolveDir: packageDir,
      sourcefile: 'tts-parity-test-entry.ts',
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

const config = (ttsState = 'stopped') => ({
  runtime: { ttsState, editing: false },
  strings: { tts: 'Read aloud' },
  ui: { ttsEnabled: true, labels: { tts: 'Read aloud' } },
  ttsNormalizationRules: [
    { pattern: String.raw`[\u0009-\u000D\u0020\u00A0]+`, replacement: ' ' },
    { pattern: String.raw`^[ "“”‘’]+|[ "“”‘’]+$`, replacement: '' },
    { pattern: String.raw`\.{2,}`, replacement: '…' },
    { pattern: String.raw`(?![%$+/@＠&=°§·…×÷<>≤≥≈≠±‰€£¥₹₩¢。、！？；：「」『』（）【】，．･＋／＝％＆＜＞￥＄〜～√⁄⋅‱′″∞.,!?;:'"“”‘’‐‑‒–—―−⁓⸺⸻﹘﹣－-])[\p{S}\p{P}\p{Cc}\p{Cs}]`, replacement: ' ' },
    { pattern: ' +', replacement: ' ' },
    { pattern: String.raw`^(?:[‐‑‒–—―−⁓⸺⸻﹘﹣－-] *){3,}$`, replacement: '' },
    { pattern: String.raw`^[\p{M}\p{Cf} ]+$`, replacement: '' },
  ],
});

browserTest(test, 'disabled TTS controller does not paint through visible descendants and returns when re-enabled', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 432, height: 900 } });
    await page.setContent('<main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>Chapter text.</p></nekori-chapter></main>');
    await page.addStyleTag({ content: `${await readerContentCss}\n${await readerControlsCss}\n#nekori-tts-controller button, #nekori-tts-controller svg { visibility: visible; }` });
    await page.addScriptTag({ content: await bundleTts() });
    const result = await page.evaluate((readerConfig) => {
      const root = document.querySelector('#reader');
      const harness = window.installTts(root, readerConfig);
      const controller = document.querySelector('#nekori-tts-controller');
      const button = controller.querySelector('button');
      const svg = button.querySelector('svg');
      harness.controller.setEnabled(false);
      const disabled = {
        hiddenClass: controller.classList.contains('hidden'),
        display: getComputedStyle(controller).display,
        parentVisibility: getComputedStyle(controller).visibility,
        buttonVisibility: getComputedStyle(button).visibility,
        buttonRects: button.getClientRects().length,
        svgRects: svg.getClientRects().length,
      };
      harness.controller.setEnabled(true);
      const enabled = {
        hiddenClass: controller.classList.contains('hidden'),
        display: getComputedStyle(controller).display,
        buttonVisibility: getComputedStyle(button).visibility,
        buttonRects: button.getClientRects().length,
        svgRects: svg.getClientRects().length,
      };
      harness.controller.dispose();
      return { disabled, enabled };
    }, config());

    assert.deepEqual(result.disabled, {
      hiddenClass: true,
      display: 'none',
      parentVisibility: 'hidden',
      buttonVisibility: 'visible',
      buttonRects: 0,
      svgRects: 0,
    });
    assert.deepEqual(result.enabled, {
      hiddenClass: false,
      display: 'block',
      buttonVisibility: 'visible',
      buttonRects: 1,
      svgRects: 1,
    });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'paragraph indices, visibility, highlight, and seek remain aligned with leaf extraction', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 480, height: 700 } });
    await page.setContent(`<main id="reader"><nekori-chapter data-nekori-chapter-id="7">
      <h2 id="heading" style="position:absolute;top:-100px">  “Chapter   one” </h2>
      <div id="container"><p id="first">First <strong>paragraph</strong>.</p><p id="second">Second…</p></div>
      <p id="symbol-only">★★★</p><p id="emoji-only">👩‍🚀</p>
      <nekori-chapter-summary></nekori-chapter-summary>
      <p id="last">Last paragraph.</p>
    </nekori-chapter></main>`);
    await page.locator('nekori-chapter-summary').evaluate((host) => {
      host.attachShadow({ mode: 'open' }).innerHTML = '<p>Summary must not enter the spoken queue.</p>';
    });
    await page.addScriptTag({ content: await bundleTts() });
    const result = await page.evaluate((readerConfig) => {
      const root = document.querySelector('#reader');
      const calls = [];
      const layout = { enabled: true, revealElement(element) { calls.push(element.id); } };
      const harness = window.installTts(root, readerConfig, layout);
      const run = (name, payload = {}) => harness.commands.get(name)(payload, new AbortController().signal);
      const text = run('tts.extractText', { chapterId: '7' });
      run('tts.highlight', {
        chapterId: '7', paragraphIndex: 2, backgroundColor: '#ffff00', textColor: '#000000',
        style: 'underline', keepInView: true,
      });
      const highlight = document.querySelector('.nekori-tts-highlight');
      const visible = run('tts.firstVisibleParagraph', { chapterId: '7' });
      const highlightStyle = highlight?.dataset.nekoriTtsHighlightStyle;
      run('tts.clearHighlight');
      return {
        text,
        visible,
        highlightedId: highlight?.id,
        highlightStyle,
        revealCalls: calls,
        shadowText: document.querySelector('nekori-chapter-summary').shadowRoot.textContent,
      };
    }, config());

    assert.deepEqual(result, {
      text: 'Chapter one\nFirst paragraph.\nSecond…\nLast paragraph.',
      visible: 1,
      highlightedId: 'second',
      highlightStyle: 'underline',
      revealCalls: ['second'],
      shadowText: 'Summary must not enter the spoken queue.',
    });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'floating controller preserves dock storage, drag seek, cancel, resize, and play state', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 400, height: 800 } });
    await page.setContent(`<style>#nekori-tts-controller { position: fixed; z-index: 3; }</style>
      <main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p id="paragraph">A readable paragraph.</p></nekori-chapter></main>`);
    await page.addScriptTag({ content: await bundleTts() });
    const result = await page.evaluate(async (readerConfig) => {
      HTMLElement.prototype.setPointerCapture = function () {};
      HTMLElement.prototype.hasPointerCapture = function () { return false; };
      const storage = new Map();
      Object.defineProperty(window, 'localStorage', { configurable: true, value: {
        getItem(key) { return storage.get(key) ?? null; },
        setItem(key, value) { storage.set(key, value); },
      } });
      const root = document.querySelector('#reader');
      const harness = window.installTts(root, readerConfig);
      const controller = document.querySelector('#nekori-tts-controller');
      const action = controller.querySelector('button');
      const dispatch = (target, type, x, y) => target.dispatchEvent(new PointerEvent(type, {
        pointerId: 1, pointerType: 'mouse', clientX: x, clientY: y, bubbles: true,
      }));
      const paragraph = document.querySelector('#paragraph');
      document.elementsFromPoint = () => [paragraph];
      dispatch(action, 'pointerdown', 25, 400);
      dispatch(controller, 'pointermove', 320, 500);
      dispatch(controller, 'pointerup', 320, 500);
      const dockAfterDrag = { left: controller.style.left, top: controller.style.top, transform: controller.style.transform };
      const stored = storage.get('nekori.tts.position');
      const dragEvents = harness.events;
      const dragParagraphIndex = harness.commands.get('tts.taggedParagraph')({ chapterId: '1' }, new AbortController().signal);
      harness.controller.dispose();
      const restored = window.installTts(root, readerConfig);
      const restoredController = document.querySelector('#nekori-tts-controller');
      dispatch(restoredController.querySelector('button'), 'pointerdown', 360, 500);
      dispatch(restoredController, 'pointermove', 50, 300);
      dispatch(restoredController, 'pointercancel', 50, 300);
      const afterCancel = { left: restoredController.style.left, top: restoredController.style.top };
      window.innerHeight = 180;
      window.dispatchEvent(new Event('resize'));
      const afterResize = { left: restoredController.style.left, top: restoredController.style.top };
      window.innerHeight = 800;
      window.dispatchEvent(new Event('resize'));
      const stoppedPath = restoredController.querySelector('path').getAttribute('d');
      readerConfig.runtime.ttsState = 'playing';
      restored.controller.refresh();
      await new Promise((resolve) => setTimeout(resolve, 0));
      const playingPath = restoredController.querySelector('path').getAttribute('d');
      readerConfig.runtime.ttsState = 'paused';
      restored.controller.refresh();
      await new Promise((resolve) => setTimeout(resolve, 0));
      const pausedPath = restoredController.querySelector('path').getAttribute('d');
      dispatch(restoredController.querySelector('button'), 'pointerdown', 360, 500);
      dispatch(restoredController, 'pointerup', 360, 500);
      const events = restored.events;
      restored.controller.setEnabled(false);
      const hidden = restoredController.classList.contains('hidden');
      restored.controller.dispose();
      return { dockAfterDrag, stored, dragEvents, dragParagraphIndex, afterCancel, afterResize, stoppedPath, playingPath, pausedPath, events, hidden };
    }, config());

    assert.deepEqual(result.dockAfterDrag, {
      left: 'calc(100% - 20px)', top: '500px', transform: 'translateX(-100%)',
    });
    assert.deepEqual(JSON.parse(result.stored), { right: true, top: 500 });
    assert.deepEqual(result.dragEvents, [{ name: 'tts.startAtTaggedParagraph', payload: {} }]);
    assert.equal(result.dragParagraphIndex, 0);
    assert.deepEqual(result.afterCancel, { left: 'calc(100% - 20px)', top: '500px' });
    assert.deepEqual(result.afterResize, { left: 'calc(100% - 20px)', top: '90px' });
    assert.match(result.stoppedPath, /^M144-/);
    assert.match(result.playingPath, /^M228-/);
    assert.match(result.pausedPath, /^M240-/);
    assert.deepEqual(result.events, [{ name: 'tts.toggle', payload: {} }]);
    assert.equal(result.hidden, true);
    await page.close();
  } finally {
    await browser.close();
  }
});
