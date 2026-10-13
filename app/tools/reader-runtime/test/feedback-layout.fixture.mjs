import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));

export const layoutCases = [
  { name: 'paged LTR single', direction: 'ltr', spread: 'single' },
  { name: 'paged LTR double', direction: 'ltr', spread: 'double' },
  { name: 'paged RTL single', direction: 'rtl', spread: 'single' },
  { name: 'paged RTL double', direction: 'rtl', spread: 'double' },
  { name: 'vertical-rl', direction: 'rtl', spread: 'single', vertical: true },
  { name: 'nonpaged infinite scroll', direction: 'ltr', spread: 'single', enabled: false, infinite: true },
];

const runtime = {
  editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false,
  menuVisible: false, immersive: false, ttsState: 'stopped', loadingChapter: false,
  loadingNext: false, noMoreChapters: false,
};

function inlineCssPlugin() {
  return {
    name: 'feedback-layout-inline-css',
    setup(builder) {
      builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
        path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, '')),
        namespace: 'feedback-css',
      }));
      builder.onLoad({ filter: /.*/, namespace: 'feedback-css' }, async (args) => ({
        contents: await readFile(args.path, 'utf8'), loader: 'text',
      }));
    },
  };
}

export async function compileProductionEntry() {
  const result = await build({
    absWorkingDir: packageDir, entryPoints: ['src/entries/reader.ts'], bundle: true,
    format: 'iife', platform: 'browser', target: 'chrome138', write: false, plugins: [inlineCssPlugin()],
  });
  return result.outputFiles[0].text;
}

export async function compileReaderCss() {
  const result = await build({
    absWorkingDir: packageDir, entryPoints: ['src/styles/reader.css'], bundle: true,
    platform: 'browser', target: 'chrome138', write: false,
  });
  return result.outputFiles[0].text;
}

export function readerConfig(layoutCase) {
  return {
    documentId: `feedback-${layoutCase.name.replaceAll(' ', '-')}`,
    mode: 'reader',
    novel: { id: 'novel', name: 'Layout test', path: '/novel' },
    chapter: { id: '1', name: 'Current', path: '/one', progress: 0 },
    nextChapter: { id: '2', name: 'Next', path: '/two', progress: 0 },
    chapters: [
      { id: '1', name: 'Current', path: '/one', progress: 0 },
      { id: '2', name: 'Next', path: '/two', progress: 0 },
    ],
    strings: { tts: 'Read aloud', imageClose: 'Close image' },
    autoSaveInterval: 1000,
    scrollTracking: { infiniteScrollEnabled: layoutCase.infinite ?? false, loadThreshold: 0.8, doneThreshold: 0.95 },
    runtime: { ...runtime, infinite: layoutCase.infinite ?? false },
    ui: { bionicEnabled: true, ttsEnabled: true, labels: { tts: 'Read aloud', imageClose: 'Close image' } },
    layout: {
      enabled: layoutCase.enabled ?? true,
      spread: layoutCase.spread,
      direction: layoutCase.direction,
      vertical: layoutCase.vertical ?? false,
      infinite: layoutCase.infinite ?? false,
      threshold: 0.8,
      chapterId: '1',
    },
  };
}

function paragraphs(prefix, count) {
  return Array.from({ length: count }, (_, index) =>
    `<p id="${prefix}-${index}">${prefix.toUpperCase()} ${index}: ${'Long reader prose keeps this chapter measurable and gives each unit visible text. '.repeat(3)}</p>`).join('');
}

export async function mountProductionReader(page, entry, css, config, boundaryChapterId) {
  const hasFutureChapter = boundaryChapterId !== '1';
  await page.setContent(`<!doctype html><html lang="en"><head><style>
    ${css}
    :root { --reader-margin-top: 24px; --reader-margin-bottom: 24px; --reader-margin-left: 20px; --reader-margin-right: 20px;
      --reader-font-size: 16px; --reader-line-height: 1.45; --reader-paragraph-spacing: 8px; }
    html, body { width: 100%; min-height: 100%; margin: 0; overflow-x: hidden; overflow-y: auto; }
    #reader { box-sizing: border-box; width: 100vw; min-height: 100vh; }
    #reader.is-paged { height: 100vh; column-width: 390px; column-gap: 40px; }
    #reader[data-reader-spread="double"] { column-width: 175px; }
    #reader[data-reader-writing-mode="vertical-rl"] { writing-mode: vertical-rl; }
  </style></head><body>
    <script>window.__nativeMessages=[];window.__nekoriNativeTransport={onmessage:null,postMessage(raw){window.__nativeMessages.push(JSON.parse(raw));}};
    window.__deliverNative=(message)=>window.__nekoriNativeTransport.onmessage?.({data:JSON.stringify(message)});</script>
    <script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script>
    <main id="reader" data-nekori-reader-root class="nekori-reader">
      <nekori-chapter class="nekori-chapter" data-nekori-chapter-id="1">${paragraphs('current', 54)}</nekori-chapter>
      ${hasFutureChapter ? `<nekori-chapter class="nekori-chapter" data-nekori-chapter-id="2">${paragraphs('future', 42)}</nekori-chapter>` : ''}
    </main></body></html>`);
  await page.addScriptTag({ content: entry });
  await page.waitForFunction(() => window.__nativeMessages.some((message) => message.kind === 'ready'));
}

export async function sendNativeCommand(page, requestId, method, payload = {}) {
  await page.evaluate(({ requestId, method, payload }) => window.__deliverNative({
    protocolVersion: 1, documentId: window.nekori.config.documentId,
    kind: 'command', requestId, method, payload,
  }), { requestId, method, payload });
  await page.waitForFunction((id) => window.__nativeMessages.some(
    (message) => message.kind === 'result' && message.requestId === id,
  ), requestId);
  return page.evaluate((id) => window.__nativeMessages.find((message) => message.requestId === id), requestId);
}

export async function captureReadingState(page, layoutEnabled) {
  return page.evaluate((paged) => {
    const root = document.querySelector('#reader');
    const position = window.nekori.layout.position;
    const bounds = paged ? root.getBoundingClientRect() :
      { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
    const visible = Array.from(root.querySelectorAll('p')).find((paragraph) => {
      const range = document.createRange();
      range.selectNodeContents(paragraph.firstChild);
      const rect = range.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && rect.right > bounds.left && rect.left < bounds.right &&
        rect.bottom > bounds.top && rect.top < bounds.bottom;
    })?.id;
    return {
      top: paged ? root.scrollTop : window.scrollY,
      left: paged ? root.scrollLeft : window.scrollX,
      unitIndex: position?.unitIndex, chapterId: position?.chapterId, visible,
    };
  }, layoutEnabled);
}

export async function boundaryCardVisible(page, layoutEnabled, chapterId = '2') {
  return page.evaluate(({ paged, chapterId: targetId }) => {
    const root = document.querySelector('#reader');
    const host = root.querySelector(`[data-nekori-chapter-id="${targetId}"] nekori-reader-feedback`);
    const card = host.shadowRoot.querySelector('.card');
    const cardBounds = card.getBoundingClientRect();
    const bounds = paged ? root.getBoundingClientRect() :
      { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
    return {
      chapterId: window.nekori.layout.position?.chapterId,
      visible: cardBounds.width > 0 && cardBounds.height > 0 && cardBounds.right > bounds.left && cardBounds.left < bounds.right &&
        cardBounds.bottom > bounds.top && cardBounds.top < bounds.bottom,
      card: { top: cardBounds.top, bottom: cardBounds.bottom }, root: { top: bounds.top, bottom: bounds.bottom },
      scroll: { top: paged ? root.scrollTop : window.scrollY, height: document.documentElement.scrollHeight,
        clientHeight: window.innerHeight },
    };
  }, { paged: layoutEnabled, chapterId });
}
