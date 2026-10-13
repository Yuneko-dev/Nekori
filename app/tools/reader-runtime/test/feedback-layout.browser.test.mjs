import assert from 'node:assert/strict';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';
import {
  boundaryCardVisible,
  captureReadingState,
  compileProductionEntry,
  compileReaderCss,
  layoutCases,
  mountProductionReader,
  readerConfig,
  sendNativeCommand,
} from './feedback-layout.fixture.mjs';

const boundaryError = (traceBase64) => ({
  severity: 'error', presentation: 'boundary', message: 'Append failed',
  detailsLabel: 'Details', dismissLabel: 'Close', traceBase64,
});
const targetCases = [
  { name: 'future chapter', chapterId: '2', progress: 30 },
  { name: 'current chapter at prefetch threshold', chapterId: '1', progress: 80 },
];

browserTest(test, 'paged reader ignores vertical progress and prefetch while both controllers are installed', async () => {
  const entry = await compileProductionEntry();
  const css = await compileReaderCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 640 } });
    const config = readerConfig({ name: 'paged infinite', direction: 'ltr', spread: 'single', infinite: true });
    await mountProductionReader(page, entry, css, config, '1');
    await page.waitForFunction(() => window.nekori.layout.position?.unitIndex === 0);
    await sendNativeCommand(page, 'seek-middle', 'layout.seekUnit', { unitIndex: 4 });
    await page.waitForFunction(() => window.nekori.layout.position?.unitIndex === 4);
    await page.evaluate(() => {
      window.__nativeMessages.length = 0;
      window.dispatchEvent(new Event('scroll'));
      window.dispatchEvent(new Event('scrollend'));
    });
    await page.waitForTimeout(700);
    const unexpected = await page.evaluate(() => window.__nativeMessages.filter(message =>
      message.method === 'navigation.loadNext' ||
      (['scroll.progressPreview', 'scroll.progressPersist'].includes(message.method) && message.payload.progress === 0)));
    assert.deepEqual(unexpected, [], 'vertical controller must not publish progress or prefetch in a paged document');
  } finally {
    await browser.close();
  }
});

browserTest(test, 'production feedback boundary survives real reader reflow and navigation in every layout', async (t) => {
  const entry = await compileProductionEntry();
  const css = await compileReaderCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    for (const layoutCase of layoutCases) {
      for (const targetCase of targetCases) await t.test(`${layoutCase.name}: ${targetCase.name}`, async () => {
        const page = await browser.newPage({ viewport: { width: 390, height: 640 } });
        const config = readerConfig(layoutCase);
        await mountProductionReader(page, entry, css, config, targetCase.chapterId);
        const send = (id, method, payload) => sendNativeCommand(page, id, method, payload);
        if (config.layout.enabled) {
          try {
            await page.waitForFunction(() => window.nekori?.layout?.position?.chapterId === '1', null, { timeout: 3000 });
          } catch (error) {
            const state = await page.evaluate(() => ({
              ready: Boolean(window.nekori), position: window.nekori?.layout?.position,
              messages: window.__nativeMessages, rootClass: document.querySelector('#reader')?.className,
              layoutMode: document.querySelector('#reader')?.dataset.readerWritingMode,
            }));
            throw new Error(`initial real layout position was not published: ${JSON.stringify(state)}`, { cause: error });
          }
          if (targetCase.chapterId === '1') {
            await send('seek-prefetch-threshold', 'layout.seekPercent', { percent: targetCase.progress });
            try {
              await page.waitForFunction(() => window.nekori.layout.position?.progress >= 0.7, null, { timeout: 3000 });
            } catch (error) {
              const state = await page.evaluate(() => ({ position: window.nekori.layout.position,
                offset: document.querySelector('#reader').scrollLeft, rootClass: document.querySelector('#reader').className }));
              throw new Error(`reader did not reach prefetch threshold: ${JSON.stringify(state)}`, { cause: error });
            }
          } else {
            await send('seek-interior', 'layout.seekUnit', { unitIndex: 3 });
            await page.waitForFunction(() => window.nekori.layout.position?.unitIndex === 3);
          }
        } else {
          await page.waitForFunction(() => Boolean(window.nekori));
          await page.evaluate(() => {
            document.documentElement.style.scrollBehavior = 'auto';
            document.documentElement.scrollTop = (document.documentElement.scrollHeight - window.innerHeight) * 0.8;
          });
          await page.waitForTimeout(80);
        }
        const before = await captureReadingState(page, config.layout.enabled);
        const stack = targetCase.chapterId === '1'
          ? 'AppendError: source failed\n'.repeat(100)
          : 'AppendError: source failed';
        const traceBase64 = Buffer.from(stack).toString('base64');
        const payload = {
          ...boundaryError(traceBase64),
          chapterId: targetCase.chapterId,
          summary: targetCase.chapterId === '1'
            ? 'Append failed\n'.repeat(180)
            : 'Could not append next chapter',
        };
        const shown = await send('feedback-show', 'feedback.show', payload);
        assert.equal(shown.error, undefined, JSON.stringify(shown));
        await page.waitForTimeout(120);

        const inserted = await page.evaluate(({ layoutEnabled, targetChapterId }) => {
          const root = document.querySelector('#reader');
          const chapter = root.querySelector(`[data-nekori-chapter-id="${targetChapterId}"]`);
          const host = chapter.querySelector('nekori-reader-feedback');
          return {
            parentIsTarget: host?.parentElement === chapter,
            endpoint: host === chapter.lastElementChild,
            shadowMode: host?.shadowRoot?.mode,
            role: host?.shadowRoot?.querySelector('.card')?.getAttribute('role'),
            proseCount: root.querySelectorAll('nekori-chapter p').length,
            bionicCount: host?.shadowRoot?.querySelectorAll('[data-nekori-bionic]').length,
            top: layoutEnabled ? root.scrollTop : window.scrollY,
            left: layoutEnabled ? root.scrollLeft : window.scrollX,
            unitIndex: window.nekori.layout.position?.unitIndex,
            chapterId: window.nekori.layout.position?.chapterId,
          };
        }, { layoutEnabled: config.layout.enabled, targetChapterId: targetCase.chapterId });
        assert.equal(inserted.parentIsTarget, true);
        assert.equal(inserted.endpoint, true);
        assert.equal(inserted.shadowMode, 'open');
        assert.equal(inserted.role, 'alert');
        assert.equal(inserted.proseCount, targetCase.chapterId === '1' ? 54 : 96);
        assert.equal(inserted.bionicCount, 0);
        assert.deepEqual(await captureReadingState(page, config.layout.enabled), before,
          `inserting a future card changed the current reading position: ${JSON.stringify({ before, inserted })}`);

        await send('tts-query', 'tts.extractText', { chapterId: targetCase.chapterId });
        const tts = await page.evaluate(() => window.__nativeMessages.find((message) => message.requestId === 'tts-query')?.payload);
        const expectedTtsPrefix = targetCase.chapterId === '1' ? 'CURRENT 0:' : 'FUTURE 0:';
        assert.ok(typeof tts === 'string' && tts.startsWith(expectedTtsPrefix), 'feedback must stay outside extracted TTS text');

        await page.locator('nekori-reader-feedback').evaluate((host) => { host.shadowRoot.querySelector('details').open = true; });
        await page.waitForTimeout(120);
        assert.deepEqual(await captureReadingState(page, config.layout.enabled), before,
          'opening details on a future card preserves the current reading position');

        await send('feedback-clear-before-navigation', 'feedback.clear');
        await page.waitForTimeout(120);
        assert.deepEqual(await captureReadingState(page, config.layout.enabled), before,
          'removing a future card preserves the current reading position');
        await send('feedback-show-again', 'feedback.show', payload);
        await page.waitForTimeout(120);

        if (config.layout.enabled) {
          for (let attempt = 0; attempt < 100; attempt++) {
            if ((await boundaryCardVisible(page, true, targetCase.chapterId)).visible) break;
            const result = await send(`next-${attempt}`, 'layout.move', { delta: 1 });
            if (result.payload === false) break;
            await page.waitForTimeout(35);
          }
          await page.waitForFunction((chapterId) => window.nekori.layout.position?.chapterId === chapterId, targetCase.chapterId);
        } else {
          await page.evaluate(() => {
            document.documentElement.style.scrollBehavior = 'auto';
            document.documentElement.scrollTop = document.documentElement.scrollHeight;
          });
          await page.waitForTimeout(100);
        }
        const reachable = await boundaryCardVisible(page, config.layout.enabled, targetCase.chapterId);
        if (config.layout.enabled) assert.equal(reachable.chapterId, targetCase.chapterId);
        else assert.equal(Boolean(reachable.chapterId), false);
        assert.equal(reachable.visible, true,
          `normal navigation reaches the boundary card at the failed chapter end: ${JSON.stringify(reachable)}`);

        const removed = await send('feedback-clear', 'feedback.clear');
        assert.equal(removed.error, undefined);
        await page.waitForTimeout(100);
        assert.equal(await page.locator('nekori-reader-feedback').count(), 0);
        await page.evaluate(() => window.nekori?.dispose());
        await page.close();
      });
    }
  } finally {
    await browser.close();
  }
});
