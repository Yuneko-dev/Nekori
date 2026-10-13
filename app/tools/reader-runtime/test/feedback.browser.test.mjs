import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const inlineCssPlugin = {
  name: 'inline-css',
  setup(builder) {
    builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
      path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
      namespace: 'inline-css',
    }));
    builder.onLoad({ filter: /.*/, namespace: 'inline-css' }, async (args) => ({
      contents: await readFile(args.path, 'utf8'),
      loader: 'text',
    }));
  },
};

const bundle = build({
  absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
  entryPoints: ['../browser-test/widgets-browser-entry.ts'],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'chrome138',
  write: false,
  plugins: [inlineCssPlugin],
});
const globalFeedbackCss = readFile(new URL('../src/styles/reader-feedback.css', import.meta.url), 'utf8');

async function createPage(browser, html = '<main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>First paragraph.</p><p>Second paragraph.</p></nekori-chapter></main>') {
  const page = await browser.newPage();
  await page.setContent(html);
  await page.addScriptTag({ content: (await bundle).outputFiles[0].text });
  return page;
}

browserTest(test, 'feedback uses an isolated in-flow boundary card with literal details and working actions', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await createPage(browser);
    const encodedTrace = Buffer.from('Error: bad <img src=x>\n at chapter()', 'utf8').toString('base64');
    const result = await page.evaluate(async (traceBase64) => {
      const root = document.querySelector('#reader');
      const calls = { reflow: 0 };
      const layout = { enabled: true, reflow() { calls.reflow++; return true; } };
      const widgets = window.makeReaderWidgets(root, layout);
      widgets.commands.get('ui.setBionic')({ enabled: true });
      widgets.commands.get('feedback.show')({
        severity: 'error', presentation: 'boundary', chapterId: '1', category: 'Network',
        summary: 'Could not append', detailsLabel: 'Show details', dismissLabel: 'Close',
        retryLabel: 'Retry', copyLabel: 'Copy', traceBase64,
      });
      const feedback = root.querySelector('nekori-chapter').lastElementChild;
      const shadow = feedback.shadowRoot;
      const details = shadow.querySelector('details');
      const tts = widgets.commands.get('tts.extractText')({ chapterId: '1' });
      const snapshot = {
        isLastChild: feedback === root.querySelector('nekori-chapter').lastElementChild,
        shadowMode: shadow.mode,
        hostPosition: getComputedStyle(feedback).position,
        hostZIndex: getComputedStyle(feedback).zIndex,
        detailsOpen: details.open,
        trace: shadow.querySelector('pre').textContent,
        traceImageCount: shadow.querySelectorAll('img').length,
        chapterParagraphs: root.querySelectorAll('nekori-chapter p').length,
        bionicInsideCard: shadow.querySelectorAll('b[data-nekori-bionic]').length,
        tts,
      };
      shadow.querySelector('button.action-copy').click();
      details.open = true;
      await new Promise((resolve) => setTimeout(resolve, 0));
      shadow.querySelector('button.action-retry').click();
      const retryRemovedCard = !root.querySelector('nekori-reader-feedback');
      const events = widgets.events.filter(([name]) => name === 'clipboard.copy' || name === 'feedback.retry');
      widgets.commands.get('feedback.show')({ presentation: 'boundary', chapterId: '1', severity: 'info', message: 'Finished', dismissLabel: 'Close' });
      root.querySelector('nekori-reader-feedback').shadowRoot.querySelector('button.action-dismiss').click();
      const closeRetryCount = widgets.events.filter(([name]) => name === 'feedback.retry').length;
      const afterActions = { retryRemovedCard, exists: !!root.querySelector('nekori-reader-feedback'), reflows: calls.reflow, closeRetryCount };
      widgets.dispose();
      return { snapshot, events, afterActions };
    }, encodedTrace);

    assert.deepEqual(result.snapshot, {
      isLastChild: true,
      shadowMode: 'open',
      hostPosition: 'static',
      hostZIndex: 'auto',
      detailsOpen: false,
      trace: 'Error: bad <img src=x>\n at chapter()',
      traceImageCount: 0,
      chapterParagraphs: 2,
      bionicInsideCard: 0,
      tts: 'First paragraph.\nSecond paragraph.',
    });
    assert.deepEqual(result.events, [
      ['clipboard.copy', { text: 'Error: bad <img src=x>\n at chapter()' }],
      ['feedback.retry', {}],
    ]);
    assert.deepEqual(result.afterActions, { retryRemovedCard: true, exists: false, reflows: 5, closeRetryCount: 1 });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'full-page feedback defaults to error, keeps details open, and info uses neutral colors', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await createPage(browser, `
      <style>
        :root { --md-sys-color-error-container: rgb(250, 210, 210); --md-sys-color-on-error-container: rgb(70, 0, 0); --md-sys-color-surface-container: rgb(230, 230, 230); }
        body { font: 24px/1.6 Georgia, serif; }
        #reader { width: 900px; height: 200px; overflow: auto; }
        #reader nekori-reader-feedback { position: fixed !important; z-index: 9999999 !important; }
      </style>
      <main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>${'Reader prose. '.repeat(160)}</p></nekori-chapter></main>
    `);
    const traceBase64 = Buffer.from('Stack trace\n</code><img src=x>', 'utf8').toString('base64');
    const result = await page.evaluate((encodedTrace) => {
      const root = document.querySelector('#reader');
      const widgets = window.makeReaderWidgets(root);
      root.scrollTop = 90;
      widgets.commands.get('feedback.show')({ message: 'Reader could not load', traceBase64: encodedTrace, detailsLabel: 'Details' });
      const error = root.querySelector('nekori-reader-feedback');
      const errorCard = error.shadowRoot.querySelector('.card');
      const errorState = {
        severity: error.dataset.severity,
        presentation: error.dataset.presentation,
        role: errorCard.getAttribute('role'),
        hostPosition: getComputedStyle(error).position,
        hostZIndex: getComputedStyle(error).zIndex,
        cardFont: getComputedStyle(errorCard).fontFamily,
        cardFontSize: getComputedStyle(errorCard).fontSize,
        sourceFontSize: getComputedStyle(root.querySelector('p')).fontSize,
        detailsOpen: error.shadowRoot.querySelector('details').open,
        trace: error.shadowRoot.querySelector('pre').textContent,
        traceImages: error.shadowRoot.querySelectorAll('img').length,
        dismissButtons: error.shadowRoot.querySelectorAll('button.action-dismiss').length,
        emptyActions: error.shadowRoot.querySelectorAll('.actions:empty').length,
        scrollTop: root.scrollTop,
        cardMarginLeft: getComputedStyle(errorCard).marginLeft,
        errorBackground: getComputedStyle(errorCard).backgroundColor,
      };
      widgets.commands.get('feedback.show')({ severity: 'info', presentation: 'fullpage', message: 'No more chapters' });
      const info = root.querySelector('nekori-reader-feedback');
      const infoCard = info.shadowRoot.querySelector('.card');
      const infoState = {
        severity: info.dataset.severity,
        role: infoCard.getAttribute('role'),
        infoBackground: getComputedStyle(infoCard).backgroundColor,
        inReaderUi: !!document.querySelector('#reader-ui nekori-reader-feedback'),
      };
      widgets.dispose();
      return { errorState, infoState };
    }, traceBase64);
    assert.equal(result.errorState.severity, 'error');
    assert.equal(result.errorState.presentation, 'fullpage');
    assert.equal(result.errorState.role, 'alert');
    assert.equal(result.errorState.hostPosition, 'static');
    assert.equal(result.errorState.hostZIndex, 'auto');
    assert.match(result.errorState.cardFont, /system-ui/);
    assert.equal(result.errorState.cardFontSize, '16px');
    assert.equal(result.errorState.sourceFontSize, '24px');
    assert.equal(result.errorState.detailsOpen, true);
    assert.equal(result.errorState.trace, 'Stack trace\n</code><img src=x>');
    assert.equal(result.errorState.traceImages, 0);
    assert.equal(result.errorState.dismissButtons, 0);
    assert.equal(result.errorState.emptyActions, 0);
    assert.equal(result.errorState.scrollTop, 90);
    assert.notEqual(result.errorState.cardMarginLeft, '0px');
    assert.deepEqual(result.infoState, {
      severity: 'info',
      role: 'status',
      infoBackground: result.errorState.errorBackground,
      inReaderUi: false,
    });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'global reader feedback stylesheet does not style source elements with generic card classes', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await createPage(browser, `
      <main id="reader"><nekori-chapter data-nekori-chapter-id="1">
        <section class="card">Source card</section>
        <p class="summary">Source summary</p>
        <div class="actions">Source actions</div>
      </nekori-chapter></main>
    `);
    await page.addStyleTag({ content: await globalFeedbackCss });
    const styles = await page.evaluate(() => Array.from(document.querySelectorAll('#reader .card, #reader .summary, #reader .actions'))
      .map((element) => {
        const style = getComputedStyle(element);
        return { background: style.backgroundColor, radius: style.borderRadius, shadow: style.boxShadow, display: style.display };
      }));
    assert.deepEqual(styles, [
      { background: 'rgba(0, 0, 0, 0)', radius: '0px', shadow: 'none', display: 'block' },
      { background: 'rgba(0, 0, 0, 0)', radius: '0px', shadow: 'none', display: 'block' },
      { background: 'rgba(0, 0, 0, 0)', radius: '0px', shadow: 'none', display: 'block' },
    ]);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'boundary cards stay in flow in paged LTR/RTL and vertical layouts without moving the viewport', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await createPage(browser, `
      <style>
        #reader { box-sizing: border-box; width: 360px; height: 280px; overflow: auto; }
        #reader.paged { column-width: 360px; column-gap: 0; }
        #reader.double { column-width: 180px; }
        #reader.vertical { writing-mode: vertical-rl; width: 280px; height: 360px; }
        nekori-chapter { break-inside: auto; }
      </style>
      <main id="reader"><nekori-chapter data-nekori-chapter-id="1"><p>${'Reader paragraph. '.repeat(180)}</p></nekori-chapter><nekori-chapter data-nekori-chapter-id="2"><p>${'Later paragraph. '.repeat(90)}</p></nekori-chapter></main>
    `);
    const actual = await page.evaluate(() => {
      const root = document.querySelector('#reader');
      let scrollCalls = 0;
      const originalScrollIntoView = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = function () { scrollCalls++; };
      const cases = [
        { label: 'paged-ltr-single', classes: ['paged'], dir: 'ltr' },
        { label: 'paged-rtl-double', classes: ['paged', 'double'], dir: 'rtl' },
        { label: 'vertical-ltr-single', classes: ['vertical'], dir: 'ltr' },
        { label: 'vertical-rtl-double', classes: ['vertical', 'double'], dir: 'rtl' },
      ];
      const results = cases.map((item) => {
        root.className = item.classes.join(' ');
        root.dir = item.dir;
        const layout = { enabled: true, reflow() { return true; } };
        const widgets = window.makeReaderWidgets(root, layout);
        root.scrollTop = 45;
        root.scrollLeft = 45;
        const beforeOffset = { top: root.scrollTop, left: root.scrollLeft };
        widgets.commands.get('feedback.show')({
          presentation: 'boundary', chapterId: '2', severity: 'error', message: item.label,
          dismissLabel: 'Close',
        });
        const host = root.querySelector('nekori-reader-feedback');
        const chapter = root.querySelector('[data-nekori-chapter-id="1"]');
        const futureChapter = root.querySelector('[data-nekori-chapter-id="2"]');
        const targetedFutureChapter = host.parentElement === futureChapter;
        const exactTargetScrollUnchanged = root.scrollTop === beforeOffset.top && root.scrollLeft === beforeOffset.left;
        widgets.commands.get('feedback.clear')({});
        widgets.commands.get('feedback.show')({
          presentation: 'boundary', chapterId: 'missing-prefetch-id', severity: 'error', message: item.label,
          dismissLabel: 'Close',
        });
        const fallbackHost = root.querySelector('nekori-reader-feedback');
        const result = {
          label: item.label,
          targetedFutureChapter,
          exactTargetScrollUnchanged,
          fallbackCurrentChapter: fallbackHost.parentElement === chapter,
          position: getComputedStyle(fallbackHost).position,
          display: getComputedStyle(fallbackHost).display,
          scrollOffsetUnchanged: root.scrollTop === beforeOffset.top && root.scrollLeft === beforeOffset.left,
          scrollCalls,
        };
        widgets.dispose();
        return result;
      });
      Element.prototype.scrollIntoView = originalScrollIntoView;
      return results;
    });
    assert.deepEqual(actual.map(({ label, targetedFutureChapter, exactTargetScrollUnchanged, fallbackCurrentChapter, position, display, scrollOffsetUnchanged, scrollCalls }) => ({
      label, targetedFutureChapter, exactTargetScrollUnchanged, fallbackCurrentChapter, position, display, scrollOffsetUnchanged, scrollCalls,
    })), [
      { label: 'paged-ltr-single', targetedFutureChapter: true, exactTargetScrollUnchanged: true, fallbackCurrentChapter: true, position: 'static', display: 'block', scrollOffsetUnchanged: true, scrollCalls: 0 },
      { label: 'paged-rtl-double', targetedFutureChapter: true, exactTargetScrollUnchanged: true, fallbackCurrentChapter: true, position: 'static', display: 'block', scrollOffsetUnchanged: true, scrollCalls: 0 },
      { label: 'vertical-ltr-single', targetedFutureChapter: true, exactTargetScrollUnchanged: true, fallbackCurrentChapter: true, position: 'static', display: 'block', scrollOffsetUnchanged: true, scrollCalls: 0 },
      { label: 'vertical-rtl-double', targetedFutureChapter: true, exactTargetScrollUnchanged: true, fallbackCurrentChapter: true, position: 'static', display: 'block', scrollOffsetUnchanged: true, scrollCalls: 0 },
    ]);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'missing boundary chapter falls back to the reader root rather than the fixed reader UI', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await createPage(browser, '<main id="reader"><div id="reader-ui"></div></main>');
    const result = await page.evaluate(() => {
      const root = document.querySelector('#reader');
      const widgets = window.makeReaderWidgets(root);
      widgets.commands.get('feedback.show')({
        presentation: 'boundary', chapterId: 'unavailable', severity: 'error', message: 'Append failed', dismissLabel: 'Close',
      });
      const feedback = root.querySelector('nekori-reader-feedback');
      const result = { isReaderRootChild: feedback.parentElement === root, isReaderUiChild: feedback.parentElement.id === 'reader-ui' };
      widgets.dispose();
      return result;
    });
    assert.deepEqual(result, { isReaderRootChild: true, isReaderUiChild: false });
    await page.close();
  } finally {
    await browser.close();
  }
});

