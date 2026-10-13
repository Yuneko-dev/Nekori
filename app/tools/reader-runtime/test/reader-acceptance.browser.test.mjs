import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const runtimeDir = path.resolve(fileURLToPath(new URL('.', import.meta.url)));
const packageDir = path.resolve(runtimeDir, '..');

function inlineCssPlugin() {
  return {
    name: 'reader-acceptance-inline-css',
    setup(builder) {
      builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
        path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
        namespace: 'acceptance-css',
      }));
      builder.onLoad({ filter: /.*/, namespace: 'acceptance-css' }, async (args) => ({
        contents: await readFile(args.path, 'utf8'),
        loader: 'text',
      }));
    },
  };
}

async function bundle(entry) {
  const result = await build({
    absWorkingDir: packageDir,
    entryPoints: [entry],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'chrome138',
    write: false,
    plugins: [inlineCssPlugin()],
  });
  return result.outputFiles[0].text;
}

async function readerCss() {
  const result = await build({
    absWorkingDir: packageDir,
    entryPoints: ['src/styles/reader.css'],
    bundle: true,
    write: false,
    platform: 'browser',
    target: 'chrome138',
  });
  return result.outputFiles[0].text;
}

function config(overrides = {}) {
  const chapter = { id: '1', name: 'Chapter one', path: '/one', progress: 0 };
  return {
    documentId: 'acceptance-document', mode: 'reader',
    novel: { id: 'novel-1', name: 'Acceptance novel', path: '/novel' },
    chapter, nextChapter: null, chapters: [chapter],
    strings: { tts: 'Read aloud', imageClose: 'Close image' }, autoSaveInterval: 1000,
    runtime: {
      editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
      menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
      loadingNext: false, noMoreChapters: false,
    },
    ui: { bionicEnabled: false, ttsEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
    ...overrides,
  };
}

const margins = `:root {
  --reader-margin-top: 30px; --reader-margin-bottom: 20px;
  --reader-margin-left: 20px; --reader-margin-right: 20px;
  --reader-font-size: 20px; --reader-line-height: 1.6;
  --reader-paragraph-spacing: 12px; --reader-paragraph-indent: 0;
  --reader-text-color: #111; --reader-background-color: #fff;
}`;

async function mountModuleReader(page, entry, markup, readerConfig, initializeWindow = null) {
  await page.setContent(`<!doctype html><html><head><style>${await readerCss()}\n${margins}</style></head><body>${markup}</body></html>`);
  if (initializeWindow) await page.evaluate(initializeWindow);
  await page.addScriptTag({ content: entry });
  await page.evaluate((value) => {
    window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value);
  }, readerConfig);
  await page.evaluate(() => window.acceptanceHarness.ready);
}

async function sendCommand(page, requestId, method, payload = {}) {
  await page.evaluate(({ requestId, method, payload }) => {
    window.acceptanceHarness.deliver({
      protocolVersion: 1, documentId: 'acceptance-document', kind: 'command', requestId, method, payload,
    });
  }, { requestId, method, payload });
  await page.waitForFunction((id) => window.acceptanceHarness.messages.some(
    (message) => message.kind === 'result' && message.requestId === id,
  ), requestId);
  return page.evaluate((id) => window.acceptanceHarness.messages.find((message) => message.requestId === id), requestId);
}

browserTest(test, 'compiled reader boots after source HTML and accepts a native layout command', async () => {
  const source = await bundle('src/entries/reader.ts');
  const css = await readerCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
    const cfg = config({
      layout: { enabled: true, spread: 'single', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: '1' },
      pluginJavaScript: `window.shellOrder.push('plugin-script'); window.pluginSawSource = Boolean(document.querySelector('.source-mark')); window.pluginSawLayout = Boolean(window.nekori?.layout);`,
    });
    await page.setContent(`<!doctype html><html><head><style>${css}\n${margins}</style>
      <script>window.shellOrder=['native-shell']; window.__nativeMessages=[];
      window.__nekoriNativeTransport={onmessage:null,postMessage(raw){window.__nativeMessages.push(JSON.parse(raw));}};
      window.__deliverNative=(message)=>window.__nekoriNativeTransport.onmessage?.({data:message});</script>
      <script id="nekori-reader-config" type="application/json">${JSON.stringify(cfg)}</script>
      </head><body><main data-nekori-reader-root id="reader"><script>window.shellOrder.push('source-script')</script>
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1"><p class="source-mark">${'A paragraph long enough to span several columns. '.repeat(50)}</p></nekori-chapter>
      </main><div id="reader-ui"></div></body></html>`);
    await page.addScriptTag({ content: source });
    await page.waitForFunction(() => window.__nativeMessages.some((message) => message.kind === 'ready'));
    const boot = await page.evaluate(() => ({
      order: window.shellOrder,
      messages: window.__nativeMessages,
      sawSource: window.pluginSawSource,
      sawLayout: window.pluginSawLayout,
      layoutPresent: Boolean(window.nekori?.layout),
      ready: Boolean(window.nekori),
    }));
    assert.deepEqual(boot.order, ['native-shell', 'source-script', 'plugin-script']);
    assert.ok(boot.messages.some((message) => message.kind === 'boot'));
    assert.ok(boot.messages.findIndex((message) => message.kind === 'boot') <
      boot.messages.findIndex((message) => message.kind === 'ready'));
    assert.ok(boot.messages.some((message) => message.kind === 'ready'));
    assert.equal(boot.sawSource, true);
    assert.equal(boot.sawLayout, true);
    assert.equal(boot.layoutPresent, true);
    assert.equal(boot.ready, true);

    await page.waitForFunction(() => {
      const root = document.querySelector('#reader');
      return root?.scrollWidth > root?.clientWidth;
    });
    await page.evaluate(() => window.__deliverNative({
      protocolVersion: 1, documentId: 'acceptance-document', kind: 'command',
      requestId: 'reflow-1', method: 'layout.reflow', payload: {},
    }));
    await page.waitForFunction(() => window.__nativeMessages.some((message) => message.kind === 'result' && message.requestId === 'reflow-1'));
    const result = await page.evaluate(() => window.__nativeMessages.find((message) => message.requestId === 'reflow-1'));
    assert.equal(result.payload, true);
    assert.ok(await page.evaluate(() => document.querySelector('#reader').classList.contains('is-paged')));
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'vertical multilingual pagination uses real CSS, one-column turns, and chapter-local reflow', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const css = await readerCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
    const verticalConfig = config({ layout: {
      enabled: true, spread: 'single', direction: 'rtl', vertical: true, infinite: false, threshold: 0.8, chapterId: '1',
    } });
    await mountModuleReader(page, entry, `<main id="reader" class="nekori-reader">
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1"><p id="start">「縦書きー」小さいゃゅょ <span class="tcy">12</span> 34 123 A12B 中文 한국어 English Tiếng Việt</p>
      ${'<p>日本語の文章、中文直排，한국어 문장. English and Tiếng Việt text.</p>'.repeat(80)}<p id="end">終わり</p></nekori-chapter></main>`, verticalConfig);
    await page.waitForFunction(() => window.acceptanceHarness.layout?.position !== null);
    const initial = await page.evaluate(() => {
      const root = document.querySelector('#reader');
      const start = root.querySelector('#start');
      const range = document.createRange();
      range.setStart(start.firstChild, 1);
      range.setEnd(start.firstChild, 2);
      const first = range.getBoundingClientRect();
      range.setStart(start.firstChild, 2);
      range.setEnd(start.firstChild, 3);
      const next = range.getBoundingClientRect();
      return {
        writingMode: getComputedStyle(root).writingMode,
        combined: getComputedStyle(root.querySelector('.tcy')).textCombineUpright,
        width: root.clientWidth, height: root.clientHeight, extent: root.scrollHeight,
        text: root.textContent, paddingBottom: getComputedStyle(root).paddingBottom,
        columnGap: getComputedStyle(root).columnGap,
        flow: { downward: next.top > first.top, sameColumn: Math.abs(first.left - next.left) < 1,
          nextColumnOnLeft: start.nextElementSibling.getBoundingClientRect().right <= start.getBoundingClientRect().left },
        overflowY: getComputedStyle(root).overflowY,
        overscroll: getComputedStyle(root).overscrollBehavior,
      };
    });
    assert.equal(initial.writingMode, 'vertical-rl');
    assert.equal(initial.combined, 'all');
    assert.equal(initial.width, 384);
    assert.equal(initial.height, 832);
    assert.equal(initial.paddingBottom, '20px');
    assert.equal(initial.columnGap, '50px');
    assert.deepEqual(initial.flow, { downward: true, sameColumn: true, nextColumnOnLeft: true });
    assert.equal(initial.overflowY, 'auto');
    assert.equal(initial.overscroll, 'none');
    assert.ok(initial.extent > initial.height * 2);
    assert.deepEqual(await page.locator('.tcy').allTextContents(), ['12', '34']);
    await page.evaluate(() => window.acceptanceHarness.layout.moveBy(1, 'instant'));
    assert.equal(await page.locator('#reader').evaluate((root) => root.scrollTop), 832);
    await page.evaluate(() => window.acceptanceHarness.layout.revealElement(document.querySelector('#end')));
    const end = await page.locator('#end').boundingBox();
    assert.ok(end.y >= 29 && end.y < 772 && end.x >= 0 && end.x < 384, JSON.stringify(end));
    await page.evaluate(() => window.acceptanceHarness.layout.seekPercent(0));
    assert.equal(await page.locator('#reader').evaluate((root) => root.scrollTop), 0);
    assert.equal(await page.locator('#reader').textContent(), initial.text);
    assert.equal(await page.evaluate(() => window.scrollY), 0);

    await page.setViewportSize({ width: 900, height: 500 });
    await page.evaluate(() => window.acceptanceHarness.layout.seekPercent(100));
    const rotatedEnd = await page.locator('#end').boundingBox();
    assert.ok(rotatedEnd.y >= 29 && rotatedEnd.y < 440 && rotatedEnd.x >= 0 && rotatedEnd.x < 900,
      JSON.stringify(rotatedEnd));
    await page.evaluate(() => {
      const chapter = document.createElement('nekori-chapter');
      chapter.className = 'nekori-chapter';
      chapter.dataset.nekoriChapterId = '2';
      chapter.innerHTML = '<p id="second">第二章 56 中文 한국어 English</p>' + '<p>多言語の文章。</p>'.repeat(70);
      document.querySelector('#reader').append(chapter);
      window.acceptanceHarness.layout.reflow();
    });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.evaluate(() => window.acceptanceHarness.layout.revealElement(document.querySelector('#second')));
    await page.waitForFunction(() => window.acceptanceHarness.layout.position?.chapterId === '2');
    assert.equal((await page.evaluate(() => window.acceptanceHarness.messages.filter(
      (message) => message.method === 'layout.positionChanged',
    ).at(-1).payload.chapterId)), '2');
    assert.ok((await page.locator('#second').boundingBox()).y < 440);
    await page.evaluate(() => {
      document.querySelector('#reader').innerHTML = '<nekori-chapter class="nekori-chapter" data-nekori-chapter-id="3"><p>短い章。</p></nekori-chapter>';
      window.acceptanceHarness.layout.reflow();
    });
    await page.waitForFunction(() => window.acceptanceHarness.layout.position?.chapterId === '3' &&
      window.acceptanceHarness.layout.position?.totalPages === 1);
    await page.evaluate(() => window.acceptanceHarness.layout.configure({
      enabled: false, spread: 'single', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: '3',
    }));
    assert.equal(await page.locator('#reader').evaluate((root) => getComputedStyle(root).writingMode), 'horizontal-tb');
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'paged CSS keeps safe margins, viewport size, and configurable widow/orphan placement', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const css = await readerCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
    for (const vertical of [false, true]) for (const bottom of [0, 24, 54]) {
      const vars = `:root { --reader-margin-top: 30px; --reader-margin-bottom: ${bottom}px;
        --reader-margin-left: 20px; --reader-margin-right: 20px; --reader-font-size: 18px;
        --reader-line-height: 1.6; --reader-paragraph-spacing: 0px; }`;
      await page.setContent(`<!doctype html><html><head><style>${css}\n${vars}</style></head><body>
        <main id="reader" class="nekori-reader"><nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1">
          ${'<p>Continuous multilingual text filling multiple pages.</p>'.repeat(80)}
        </nekori-chapter></main></body></html>`);
      const cfg = config({ layout: {
        enabled: true, spread: 'single', direction: 'ltr', vertical, infinite: false, threshold: 0.8, chapterId: '1',
      } });
      await page.addScriptTag({ content: entry });
      await page.evaluate((value) => { window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value); }, cfg);
      await page.evaluate(() => window.acceptanceHarness.ready);
      const dimensions = await page.evaluate(() => {
        const root = document.querySelector('#reader');
        const style = getComputedStyle(root);
        return { bottom: parseFloat(style.paddingBottom), gap: parseFloat(style.columnGap),
          height: root.clientHeight, width: root.clientWidth, writingMode: style.writingMode,
          overflowY: style.overflowY, overscroll: style.overscrollBehavior };
      });
      assert.equal(dimensions.bottom, bottom, `${vertical ? 'vertical' : 'horizontal'} bottom reserve`);
      assert.equal(dimensions.height, 832);
      assert.equal(dimensions.width, 384);
      assert.equal(dimensions.gap, vertical ? 30 + bottom : 40,
        JSON.stringify({ vertical, bottom, dimensions,
          className: await page.locator('#reader').getAttribute('class'),
          spread: await page.locator('#reader').getAttribute('data-reader-spread') }));
      assert.equal(dimensions.writingMode, vertical ? 'vertical-rl' : 'horizontal-tb');
      assert.equal(dimensions.overflowY, vertical ? 'auto' : 'hidden');
      assert.equal(dimensions.overscroll, 'none');
      await page.evaluate(() => window.acceptanceHarness.dispose());
    }

    await page.setViewportSize({ width: 384, height: 130 });
    await page.setContent(`<!doctype html><html><head><style>
      ${css}
      :root { --reader-margin-top: 0px; --reader-margin-bottom: 0px; --reader-margin-left: 0px;
        --reader-margin-right: 0px; --reader-font-size: 16px; --reader-line-height: 20px;
        --reader-paragraph-spacing: 0px; --reader-paragraph-indent: 0; }
      </style></head><body><main id="reader" class="nekori-reader"><nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1">
        <p>One<br>Two<br>Three<br>Four<br>Five</p><p id="next">Six<br>Seven<br>Eight</p>
      </nekori-chapter></main></body></html>`);
    const cfg = config({ layout: {
      enabled: true, spread: 'single', direction: 'ltr', vertical: false, infinite: false, threshold: 0.8, chapterId: '1',
    } });
    await page.addScriptTag({ content: entry });
    await page.evaluate((value) => { window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value); }, cfg);
    await page.evaluate(() => window.acceptanceHarness.ready);
    for (const minimum of [2, 1, 2]) {
      const left = await page.evaluate((value) => {
        document.documentElement.style.setProperty('--reader-min-fragment-lines', String(value));
        window.acceptanceHarness.layout.reflow();
        return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => {
          const range = document.createRange();
          range.selectNodeContents(document.querySelector('#next').firstChild);
          resolve(range.getBoundingClientRect().left);
        })));
      }, minimum);
      assert.equal(left, minimum === 1 ? 0 : 384, `minimum fragment lines ${minimum}`);
    }
    await page.evaluate(() => window.acceptanceHarness.dispose());
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'paged anchors stay chapter-scoped and preserve a horizontal column viewport', async (t) => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    for (const direction of ['ltr', 'rtl']) for (const spread of ['single', 'double']) {
      await t.test(`${direction} ${spread}`, async () => {
        const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
        const content = `<main id="reader" class="nekori-reader"><nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1">
          <div style="overflow:hidden"><p id="back">Opening paragraph <a href="#note">[note]</a></p>${'<p>A paragraph long enough to fill several lines in this narrow reader viewport.</p>'.repeat(45)}</div>
          ${'<p>More flowing text after the source block, before the footnote.</p>'.repeat(20)}
          <div><p id="note"><a href="#back">[back]</a> <a href="#">[top]</a> Footnote</p></div>
          <nekori-chapter-summary data-nekori-chapter-id="1">Summary</nekori-chapter-summary></nekori-chapter></main>`;
        const cfg = config({ layout: {
          enabled: true, spread, direction, vertical: false, infinite: false, threshold: 0.8, chapterId: '1',
        } });
        await mountModuleReader(page, entry, content, cfg);
        await page.waitForFunction(() => window.acceptanceHarness.layout?.position !== null);
        await page.evaluate(() => { window.anchorDispose = window.acceptanceHarness.installAnchors(); });
        for (const href of ['#note', '#back', '#note', '#', 'summary']) {
          if (href === 'summary') await sendCommand(page, 'focus-summary', 'summary.focus', { chapterId: '1' });
          else await page.evaluate((href) => document.querySelector(`a[href="${href}"]`).click(), href);
          await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
          const expectedChapter = '1';
          const state = await page.evaluate(() => {
            const root = document.querySelector('#reader');
            return { top: root.scrollTop, windowTop: window.scrollY, left: root.scrollLeft,
              width: root.clientWidth, activeChapter: window.acceptanceHarness.layout.position?.chapterId };
          });
          assert.equal(state.top, 0, 'paged column viewport remains vertically fixed');
          assert.equal(state.windowTop, 0, 'document viewport remains vertically fixed');
          assert.equal(Math.abs(state.left) % state.width, 0, 'anchor reveal aligns to a complete page');
          assert.equal(state.activeChapter, expectedChapter);
          if (href === '#back' || href === '#') assert.equal(state.left, 0);
          else assert.ok(direction === 'rtl' ? state.left < 0 : state.left > 0, JSON.stringify(state));
        }
        await page.evaluate(() => window.acceptanceHarness.layout.moveBy(2, 'instant'));
        await page.waitForFunction(() => Math.abs(document.querySelector('#reader').scrollLeft) >= 384);
        await page.waitForTimeout(220);
        const beforeSummary = await page.evaluate(() => window.acceptanceHarness.layout.position);
        assert.ok(beforeSummary.unitIndex >= 1, JSON.stringify(beforeSummary));
        await sendCommand(page, 'runtime-summary', 'summary.render', {
          chapterId: '1', state: 'ready', text: 'A real runtime summary is prepended.\n\nSecond block.',
          labels: { title: 'Summary', loading: 'Loading', cancel: 'Cancel', regenerate: 'Again', close: 'Close' },
        });
        await page.waitForTimeout(240);
        const afterReflow = await page.evaluate(() => window.acceptanceHarness.layout.position);
        assert.ok(afterReflow.unitIndex > 0, `summary insertion lost reading progress: ${JSON.stringify({ beforeSummary, afterReflow })}`);
        assert.ok(afterReflow.progress > 0.1, `summary insertion reset chapter progress: ${JSON.stringify({ beforeSummary, afterReflow })}`);
        await sendCommand(page, 'focus-runtime-summary', 'summary.focus', { chapterId: '1' });
        await page.waitForTimeout(220);
        const focusedSummary = await page.evaluate(() => {
          const root = document.querySelector('#reader');
          const bounds = root.getBoundingClientRect();
          const summary = root.querySelector('nekori-chapter-summary');
          const target = summary.getBoundingClientRect();
          return { rootTop: root.scrollTop, windowTop: window.scrollY, rootLeft: root.scrollLeft,
            rootBounds: { left: bounds.left, right: bounds.right }, target: { left: target.left, right: target.right },
            position: window.acceptanceHarness.layout.position };
        });
        assert.equal(focusedSummary.rootTop, 0);
        assert.equal(focusedSummary.windowTop, 0);
        assert.ok(focusedSummary.target.left >= focusedSummary.rootBounds.left - 1 &&
          focusedSummary.target.right <= focusedSummary.rootBounds.right + 1,
        `summary focus did not reveal its page: ${JSON.stringify(focusedSummary)}`);
        assert.ok(focusedSummary.position.unitIndex >= 0);
        await page.close();
      });
    }
  } finally {
    await browser.close();
  }
});

browserTest(test, 'ordinary fragment navigation resolves duplicate ids within the clicked chapter', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
    await mountModuleReader(page, entry, `<main id="reader">
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1"><p id="note">Wrong chapter</p></nekori-chapter>
      <div style="height:1000px"></div>
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="2"><a href="#note">Footnote</a>
        <div style="height:1000px"></div><p id="note">Correct chapter</p><div style="height:1000px"></div>
      </nekori-chapter></main>`, config());
    await page.evaluate(() => { window.anchorDispose = window.acceptanceHarness.installAnchors(); });
    await page.evaluate(() => document.querySelector('a').click());
    await page.waitForFunction(() => document.querySelector('[data-nekori-chapter-id="2"] [id="note"]')
      ?.getBoundingClientRect().top <= 1);
    const position = await page.evaluate(() => ({
      targetTop: document.querySelector('[data-nekori-chapter-id="2"] [id="note"]').getBoundingClientRect().top,
      scrollY: window.scrollY, hash: location.hash,
    }));
    assert.ok(Math.abs(position.targetTop) <= 1, JSON.stringify(position));
    assert.ok(position.scrollY > 1000, JSON.stringify(position));
    assert.equal(position.hash, '');
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'summary shadow content stays literal and outside bionic, TTS mapping, and reader gesture ownership', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    const cfg = config({ ui: { bionicEnabled: true, ttsEnabled: false,
      labels: { tts: 'Read aloud', imageClose: 'Close image' } } });
    await mountModuleReader(page, entry, `<main id="reader"><nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1"><p>Ordinary reading paragraph.</p></nekori-chapter></main>`, cfg);
    assert.equal((await sendCommand(page, 'focus-missing', 'summary.focus', { chapterId: '1' })).payload, false);
    await sendCommand(page, 'summary-render', 'summary.render', {
      chapterId: '1', state: 'ready', text: '<img src=x onerror=alert(1)>\n\nSecond <b>literal</b>',
      labels: { title: 'Summary', loading: 'Loading', cancel: 'Cancel', regenerate: 'Again', close: 'Close' },
    });
    assert.equal((await sendCommand(page, 'focus-summary', 'summary.focus', { chapterId: '1' })).payload, true);
    const result = await page.evaluate(() => {
      const chapter = document.querySelector('nekori-chapter');
      const host = chapter.querySelector(':scope > nekori-chapter-summary');
      const body = host.shadowRoot.querySelector('.body');
      const extract = window.acceptanceHarness.messages.length;
      window.acceptanceHarness.deliver({ protocolVersion: 1, documentId: 'acceptance-document', kind: 'command',
        requestId: 'extract-tts', method: 'tts.extractText', payload: { chapterId: '1' } });
      window.acceptanceHarness.deliver({ protocolVersion: 1, documentId: 'acceptance-document', kind: 'command',
        requestId: 'run-snippet', method: 'snippet.run', payload: { code: 'window.snippetSource = "  preserve\\nbytes  ";' } });
      return {
        shadowMode: host.shadowRoot.mode, chapterParagraphs: chapter.querySelectorAll('p').length,
        summaryParagraphs: body.querySelectorAll('p').length, summaryImages: body.querySelectorAll('img').length,
        literalMarkup: body.textContent.includes('<img src=x onerror=alert(1)>'),
        readerBionic: chapter.querySelector('p b[data-nekori-bionic]') !== null,
        summaryBionic: body.querySelector('b[data-nekori-bionic]') !== null,
        extract,
      };
    });
    assert.deepEqual({ ...result, extract: undefined }, {
      shadowMode: 'open', chapterParagraphs: 1, summaryParagraphs: 2, summaryImages: 0,
      literalMarkup: true, readerBionic: true, summaryBionic: false, extract: undefined,
    });
    await page.waitForFunction(() => window.acceptanceHarness.messages.some(
      (message) => message.kind === 'result' && message.requestId === 'extract-tts',
    ));
    await page.waitForFunction(() => window.snippetSource === '  preserve\nbytes  ');
    const extracted = await page.evaluate(() => window.acceptanceHarness.messages.find(
      (message) => message.requestId === 'extract-tts',
    ).payload);
    assert.equal(extracted, 'Ordinary reading paragraph.');
    const gestureOwners = await page.evaluate(() => {
      const host = document.querySelector('nekori-chapter-summary');
      host.shadowRoot.querySelector('button').dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, composed: true, isPrimary: true,
      }));
      host.shadowRoot.querySelector('.body').dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, composed: true, isPrimary: true,
      }));
      return window.acceptanceHarness.messages.filter(
        (message) => message.kind === 'event' && message.method === 'gesture.claim',
      ).map((message) => message.payload.owner);
    });
    assert.deepEqual(gestureOwners, ['blocked', 'surface']);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'TTS dock drags, restores, cancels, taps, clamps, and tolerates bad storage', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 400, height: 800 } });
    const initializeTtsStorage = () => {
      window.__ttsStorage = new Map();
      window.__ttsStorageUnavailable = false;
      Object.defineProperty(window, 'localStorage', { configurable: true, get() {
        if (window.__ttsStorageUnavailable) throw new Error('storage blocked');
        return {
          getItem(key) { return window.__ttsStorage.get(key) ?? null; },
          setItem(key, value) { window.__ttsStorage.set(key, String(value)); },
        };
      } });
    };
    const cfg = config({ ui: { bionicEnabled: false, ttsEnabled: true,
      labels: { tts: 'Read aloud', imageClose: 'Close image' } } });
    await mountModuleReader(page, entry,
      '<main id="reader" class="nekori-reader"><nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1"><p>Readable text.</p></nekori-chapter></main>',
      cfg, initializeTtsStorage);
    await page.evaluate(() => {
      window.__ttsPointerEvents = [];
      const button = document.querySelector('#nekori-tts-controller button');
      for (const name of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel']) {
        button.addEventListener(name, (event) => window.__ttsPointerEvents.push([name, event.pointerId, event.clientX, event.clientY]));
      }
    });
    const button = page.locator('#nekori-tts-controller button');
    const drag = async (targetX, targetY) => {
      const box = await button.boundingBox();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await page.mouse.move(targetX, targetY, { steps: 4 });
      await page.mouse.up();
    };
    await drag(320, 500);
    let dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({
      left: element.style.left, transform: element.style.transform, top: element.style.top,
      transition: element.style.transition,
    }));
    assert.deepEqual(dock, { left: 'calc(100% - 20px)', transform: 'translateX(-100%)', top: '500px', transition: '1s' },
      JSON.stringify(await page.evaluate(() => ({ events: window.__ttsPointerEvents, box: document.querySelector('#nekori-tts-controller button').getBoundingClientRect().toJSON() }))));
    assert.deepEqual(JSON.parse(await page.evaluate(() => window.__ttsStorage.get('nekori.tts.position'))), { right: true, top: 500 });

    await page.evaluate((value) => {
      window.acceptanceHarness.dispose();
      window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value);
    }, cfg);
    await page.evaluate(() => window.acceptanceHarness.ready);
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({ left: element.style.left, top: element.style.top }));
    assert.deepEqual(dock, { left: 'calc(100% - 20px)', top: '500px' });

    let box = await button.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(50, 300, { steps: 3 });
    await page.evaluate(() => document.querySelector('#nekori-tts-controller button').dispatchEvent(new PointerEvent('pointercancel', {
      bubbles: true, pointerId: 1,
    })));
    await page.mouse.up();
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({ left: element.style.left, top: element.style.top }));
    assert.deepEqual(dock, { left: 'calc(100% - 20px)', top: '500px' });

    box = await button.boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForFunction(() => window.acceptanceHarness.messages.some((message) => message.method === 'tts.toggle'));
    await drag(100, 50);
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({
      left: element.style.left, transform: element.style.transform, top: element.style.top,
    }));
    assert.deepEqual(dock, { left: '20px', transform: 'translateX(0px)', top: '120px' });
    await page.setViewportSize({ width: 400, height: 180 });
    assert.equal(await page.locator('#nekori-tts-controller').evaluate((element) => element.style.top), '90px');

    await page.evaluate((value) => {
      window.acceptanceHarness.dispose();
      window.__ttsStorage.set('nekori.tts.position', '{');
      window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value);
    }, cfg);
    await page.evaluate(() => window.acceptanceHarness.ready);
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({ left: element.style.left, top: element.style.top }));
    assert.deepEqual(dock, { left: '20px', top: '90px' });

    await page.setViewportSize({ width: 400, height: 800 });
    await page.evaluate((value) => {
      window.acceptanceHarness.dispose();
      window.__ttsStorage.set('nekori.tts.position', '{"right":true,"top":"bad"}');
      window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value);
    }, cfg);
    await page.evaluate(() => window.acceptanceHarness.ready);
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({ left: element.style.left, top: element.style.top }));
    assert.deepEqual(dock, { left: '20px', top: '400px' });

    await page.evaluate((value) => {
      window.acceptanceHarness.dispose();
      window.__ttsStorageUnavailable = true;
      window.acceptanceHarness = window.makeReaderAcceptanceHarness(document.querySelector('#reader'), value);
    }, cfg);
    await page.evaluate(() => window.acceptanceHarness.ready);
    await drag(320, 500);
    dock = await page.locator('#nekori-tts-controller').evaluate((element) => ({ left: element.style.left, top: element.style.top }));
    assert.deepEqual(dock, { left: 'calc(100% - 20px)', top: '500px' });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'infinite scrolling attributes a short final chapter and re-samples after load completion', async () => {
  const entry = await bundle('test/acceptance/reader-browser-entry.ts');
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 100 } });
    const cfg = config({
      runtime: { ...config().runtime, infinite: true },
      scrollTracking: { infiniteScrollEnabled: true, loadThreshold: 0.8, doneThreshold: 0.95 },
    });
    const content = `<main id="reader">
      <div class="nekori-chapter-divider" data-nekori-chapter-id="1"></div><section style="height:500px"></section>
      <div class="nekori-chapter-divider" data-nekori-chapter-id="short-last"></div><section style="height:80px"></section>
    </main>`;
    await mountModuleReader(page, entry, content, cfg);
    await page.waitForTimeout(50);
    await page.evaluate(() => {
      document.scrollingElement.scrollTop = document.scrollingElement.scrollHeight - window.innerHeight;
      window.dispatchEvent(new Event('scroll'));
    });
    await page.waitForFunction(() => window.acceptanceHarness.messages.some(
      (message) => message.method === 'navigation.loadNext',
    ));
    const first = await page.evaluate(() => window.acceptanceHarness.messages.filter(
      (message) => message.kind === 'event',
    ).map((message) => [message.method, message.payload]));
    assert.ok(first.some(([method, payload]) => method === 'chapter.visible' && payload.chapterId === 'short-last'));
    assert.equal(first.filter(([method]) => method === 'navigation.loadNext').length, 1);
    await page.evaluate(() => window.acceptanceHarness.tracking.setLoadingNext(false));
    await page.waitForFunction(() => window.acceptanceHarness.messages.filter(
      (message) => message.method === 'navigation.loadNext',
    ).length === 2);
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'title hiding and native style classes update live without removing source classes', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    const cfg = config({ style: { bodyClasses: 'nekori-reader-hide-title nekori-reader-force-style' } });
    await mountModuleReader(page, await bundle('test/acceptance/reader-browser-entry.ts'),
      '<main id="reader" class="nekori-reader"><nekori-chapter data-nekori-chapter-id="1"><h1 id="title">Title</h1><p>Text</p><h1 id="later">Later heading</h1></nekori-chapter></main>', cfg);
    await page.evaluate(() => document.body.classList.add('source-body', 'nekori-reader-hide-title', 'nekori-reader-force-style'));
    assert.equal(await page.locator('#title').evaluate((element) => getComputedStyle(element).display), 'none');
    assert.notEqual(await page.locator('#later').evaluate((element) => getComputedStyle(element).display), 'none');
    await sendCommand(page, 'show-title', 'style.apply', { css: '', bodyClasses: '' });
    assert.notEqual(await page.locator('#title').evaluate((element) => getComputedStyle(element).display), 'none');
    assert.deepEqual(await page.evaluate(() => ({
      source: document.body.classList.contains('source-body'),
      hidden: document.body.classList.contains('nekori-reader-hide-title'),
      forced: document.body.classList.contains('nekori-reader-force-style'),
    })), { source: true, hidden: false, forced: false });
  } finally {
    await browser.close();
  }
});

browserTest(test, 'video runtime ignores page progress and player spans the viewport', async () => {
  const source = await bundle('src/entries/reader.ts');
  const css = await readerCss();
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 384, height: 832 } });
    const cfg = config({
      scrollTracking: { infiniteScrollEnabled: false, loadThreshold: 0.8, doneThreshold: 0.95 },
      markShortChapterAsRead: true,
      player: { mode: 'playback', video: { type: 'iframe', path: '/one', progress: 0 } },
    });
    await page.setContent(`<style>${css}\n${margins}</style>
      <script>window.messages=[];window.__nekoriNativeTransport={onmessage:null,postMessage(raw){window.messages.push(JSON.parse(raw));}};</script>
      <script id="nekori-reader-config" type="application/json">${JSON.stringify(cfg)}</script>
      <main id="nekori-reader" class="nekori-reader" data-nekori-reader-root></main>`);
    await page.addScriptTag({ content: source });
    await page.waitForFunction(() => window.messages.some(m => m.kind === 'ready'));
    await page.evaluate(() => {
      window.dispatchEvent(new Event('scroll'));
      window.dispatchEvent(new Event('scrollend'));
    });
    await page.waitForTimeout(700);
    const result = await page.evaluate(() => {
      const rect = document.querySelector('#nekori-player-container').getBoundingClientRect();
      return { left: rect.left, right: rect.right, width: innerWidth,
        scrollEvents: window.messages.filter(m => m.method?.startsWith('scroll.progress')) };
    });
    assert.equal(result.left, 0);
    assert.equal(result.right, result.width);
    assert.deepEqual(result.scrollEvents, []);
  } finally { await browser.close(); }
});
