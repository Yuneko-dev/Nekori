import assert from 'node:assert/strict';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';
import { compileProductionEntry, compileReaderCss, layoutCases, readerConfig, sendNativeCommand } from './feedback-layout.fixture.mjs';

const hiddenClass = 'nekori-reader-hide-chapter-separator';
const variables = `:root{--reader-margin-top:31px;--reader-margin-bottom:47px;
  --reader-margin-left:17px;--reader-margin-right:23px;--reader-font-size:20px;
  --reader-line-height:1.4;--reader-paragraph-spacing:12px;--reader-paragraph-indent:0em;}`;
const assets = Promise.all([compileProductionEntry(), compileReaderCss()]);
const content = (kind, id) => kind === 'plainText'
  ? { kind, text: Array.from({ length: 18 }, (_, i) => `Chapter ${id}, paragraph ${i}`).join('\n\n') }
  : { kind, html: Array.from({ length: 18 }, (_, i) => `<p>Chapter ${id}, paragraph ${i}</p>`).join('') };

async function mount(page, kind, layoutCase = { name: 'scroll', enabled: false, infinite: true, direction: 'ltr', spread: 'single' }) {
  const [entry, css] = await assets;
  const config = { ...readerConfig(layoutCase), style: { css: variables, bodyClasses: '' } };
  if (kind === 'plainText') config.initialContent = content(kind, 1);
  const initialNodes = kind === 'html'
    ? `<div class="nekori-chapter-divider" data-nekori-chapter-id="1" style="visibility:hidden;height:0;margin:0;padding:0;border:none"></div>
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1" dir="ltr">${content(kind, 1).html}</nekori-chapter>` : '';
  await page.setContent(`<style>${css}${variables}</style><body>
    <script>window.__nativeMessages=[];window.__nekoriNativeTransport={onmessage:null,
      postMessage(raw){window.__nativeMessages.push(JSON.parse(raw));}};
      window.__deliverNative=(message)=>window.__nekoriNativeTransport.onmessage({data:JSON.stringify(message)});</script>
    <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
    <main class="nekori-reader" data-nekori-reader-root>${initialNodes}</main></body>`);
  await page.addScriptTag({ content: entry });
  await page.waitForFunction(() => window.__nativeMessages.some(message => message.kind === 'ready'));
  if (config.layout.enabled) await page.waitForFunction(() => !!window.nekori.layout.position);
}

async function settle(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function toggle(page, hidden) {
  await sendNativeCommand(page, `separator-${hidden}`, 'style.apply', { css: variables, bodyClasses: hidden ? hiddenClass : '' });
  await settle(page);
}

async function append(page, id, kind) {
  await sendNativeCommand(page, `append-${id}`, 'chapter.append', {
    chapter: { id, name: `Chapter ${id}`, number: Number(id), path: `/${id}`, absoluteUrl: `https://source.test/${id}` },
    content: content(kind, id),
  });
  await settle(page);
}

async function geometry(page) {
  return page.evaluate(() => {
    const chapters = [...document.querySelectorAll('main > nekori-chapter')];
    const markers = [...document.querySelectorAll('main > .nekori-chapter-divider')];
    const first = chapters[0].querySelector('p');
    const last = chapters.at(-1).querySelector('p:last-child');
    const rect = node => node.getBoundingClientRect();
    return {
      top: rect(first).top - rect(document.body).top,
      bottom: rect(document.body).bottom - rect(last).bottom,
      padding: [getComputedStyle(document.body).paddingTop, getComputedStyle(document.body).paddingBottom],
      ids: markers.map(node => node.dataset.nekoriChapterId),
      markers: markers.map(node => ({ height: rect(node).height, display: getComputedStyle(node).display,
        visibility: getComputedStyle(node).visibility, offset: rect(node).top + scrollY })),
      starts: chapters.map(node => rect(node).top + scrollY),
      gaps: chapters.slice(1).map((node, i) => rect(node.querySelector('p')).top - rect(chapters[i].querySelector('p:last-child')).bottom),
      paragraphGap: rect(first.nextElementSibling).top - rect(first).bottom,
    };
  });
}

for (const kind of ['plainText', 'html']) {
  browserTest(test, `hidden separators preserve outer margins and normal paragraph gaps for ${kind}`, async () => {
    const browser = await chromium.launch({ executablePath: chromiumPath });
    try {
      const page = await browser.newPage({ viewport: { width: 420, height: 640 } });
      await mount(page, kind);
      const single = await geometry(page);
      await toggle(page, true);
      assert.deepEqual(await geometry(page), single, 'single chapter keeps both outer edges');
      await toggle(page, false);
      await append(page, '2', kind);
      const visible = await geometry(page);
      assert.ok(visible.gaps[0] > visible.paragraphGap);
      await toggle(page, true);
      let hidden = await geometry(page);
      assert.equal(hidden.top, single.top);
      assert.equal(hidden.bottom, single.bottom);
      assert.deepEqual(hidden.padding, ['31px', '47px']);
      assert.deepEqual(hidden.gaps, [hidden.paragraphGap], 'join has one paragraph gap, not two chapter edges');
      await append(page, '3', kind === 'html' ? 'plainText' : 'html');
      hidden = await geometry(page);
      assert.deepEqual(hidden.ids, ['1', '2', '3']);
      assert.deepEqual(hidden.gaps, [hidden.paragraphGap, hidden.paragraphGap]);
      hidden.markers.forEach((marker, i) => {
        assert.equal(marker.height, 0);
        assert.notEqual(marker.display, 'none', 'tracking marker must stay in flow');
        assert.equal(marker.visibility, 'hidden');
        assert.equal(marker.offset, hidden.starts[i]);
      });
      await page.evaluate(offset => window.scrollTo(0, offset + 1), hidden.starts[1]);
      await page.waitForFunction(() => window.__nativeMessages.some(message =>
        message.method === 'chapter.visible' && message.payload.chapterId === '2'));
      await sendNativeCommand(page, 'remove-last', 'chapter.remove', { chapterId: '3' });
      await settle(page);
      assert.equal((await geometry(page)).bottom, single.bottom);
      await append(page, '4', kind);
      await sendNativeCommand(page, 'remove-middle', 'chapter.remove', { chapterId: '2' });
      await settle(page);
      assert.deepEqual((await geometry(page)).ids, ['1', '4']);
      assert.deepEqual((await geometry(page)).gaps, [single.paragraphGap]);
      await sendNativeCommand(page, 'remove-first', 'chapter.remove', { chapterId: '1' });
      await settle(page);
      assert.equal((await geometry(page)).top, single.top);
      assert.equal((await geometry(page)).bottom, single.bottom);
      await toggle(page, false);
      assert.equal((await geometry(page)).top, single.top, 'new first marker stays collapsed when toggled back');
    } finally { await browser.close(); }
  });
}

browserTest(test, 'hidden separators preserve margins authored inside a source wrapper', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage({ viewport: { width: 420, height: 640 } });
    await mount(page, 'html');
    await sendNativeCommand(page, 'append-authored', 'chapter.append', {
      chapter: { id: '2', name: 'Source HTML', number: 2, path: '/2', absoluteUrl: 'https://source.test/2' },
      content: { kind: 'html', html: '<div id="authored" style="margin-top:19px;margin-bottom:23px"><p style="margin-top:7px;margin-bottom:9px">Source text</p></div>' },
    });
    await append(page, '3', 'plainText');
    await toggle(page, true);
    assert.deepEqual(await page.locator('#authored').evaluate(node => {
      const wrapper = getComputedStyle(node);
      const paragraph = getComputedStyle(node.firstElementChild);
      return [wrapper.marginTop, wrapper.marginBottom, paragraph.marginTop, paragraph.marginBottom];
    }), ['19px', '23px', '7px', '9px']);
  } finally { await browser.close(); }
});

browserTest(test, 'separator preference leaves paged LTR RTL and vertical geometry unchanged', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    for (const layoutCase of layoutCases.filter(value => value.enabled !== false)) {
      const page = await browser.newPage({ viewport: { width: 420, height: 640 } });
      await mount(page, 'plainText', { ...layoutCase, infinite: true });
      await append(page, '2', 'plainText');
      const measure = () => page.evaluate(() => {
        const root = document.querySelector('main');
        const css = getComputedStyle(root);
        return { count: window.nekori.layout.position.unitCount, padding: [css.paddingTop, css.paddingRight, css.paddingBottom, css.paddingLeft],
          gap: css.columnGap, width: root.clientWidth, height: root.clientHeight };
      });
      const before = await measure();
      assert.ok(before.count > 1);
      await toggle(page, true);
      assert.deepEqual(await measure(), before, layoutCase.name);
      await sendNativeCommand(page, 'turn', 'layout.move', { delta: 1 });
      await page.waitForFunction(() => window.nekori.layout.position.unitIndex === 1);
      await page.close();
    }
  } finally { await browser.close(); }
});
