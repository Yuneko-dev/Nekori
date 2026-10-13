import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import test from 'node:test';
import { browserTest, chromium, chromiumPath } from '../../../src/test/js/browser-test-setup.mjs';

const mediaCss = readFile(new URL('../src/styles/player.css', import.meta.url), 'utf8');
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
const mediaBundle = build({
  absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
  entryPoints: ['../src/media/player-dom.ts'],
  bundle: true,
  format: 'iife',
  globalName: 'MediaDOM',
  platform: 'browser',
  target: 'chrome138',
  plugins: [inlineCssPlugin],
  write: false,
});
const debugBundle = build({
  absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
  entryPoints: ['../src/media/debug-overlay.ts'],
  bundle: true,
  format: 'iife',
  globalName: 'MediaDebug',
  platform: 'browser',
  target: 'chrome138',
  write: false,
});

browserTest(test, 'player composition keeps controls with cross-origin CSS and CDN-like tags', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.route('https://reader.test/**', (route) => route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: '<html lang="ja"><body><main id="root"></main><link rel="stylesheet" href="https://cdn.test/external.css"></body></html>',
    }));
    await page.route('https://cdn.test/external.css', (route) => route.fulfill({
      status: 200,
      contentType: 'text/css',
      body: 'body { color: rgb(1, 2, 3); }',
    }));
    await page.goto('https://reader.test/');
    await page.waitForFunction(() => document.styleSheets.length > 0);
    const css = await mediaCss;
    assert.match(css, /#nekori-reader/);
    assert.doesNotMatch(css, /lnreader|#LNReader-chapter/i);
    await page.addScriptTag({ content: (await mediaBundle).outputFiles[0].text });
    await page.addScriptTag({ content: (await debugBundle).outputFiles[0].text });
    const result = await page.evaluate(async () => {
      const makeSkin = () => class extends HTMLElement {
        constructor() {
          super();
          const shadow = this.attachShadow({ mode: 'open' });
          shadow.innerHTML = '<media-container><div class="video-controls-secondary"><media-cast-button id="cast"></media-cast-button><media-tooltip trigger="cast"></media-tooltip><media-pip-button></media-pip-button><media-status-indicator actions="play togglePictureInPicture"></media-status-indicator></div></media-container>';
        }
      };
      for (const tag of ['video-player', 'live-video-player', 'media-i18n', 'hlsjs-video', 'media-container', 'media-cast-button', 'media-tooltip', 'media-pip-button', 'media-status-indicator']) {
        customElements.define(tag, class extends HTMLElement {});
      }
      customElements.define('video-skin', makeSkin());
      customElements.define('live-video-skin', makeSkin());
      const root = document.querySelector('#root');
      const host = {
        root,
        strings: { videoResumeTitle: 'Continue?', videoSkipIntro: 'Skip intro', videoNextPlay: 'Next' },
        nextChapter: { name: 'Chapter 2' },
        emit() {}, request: async () => true, fetch: async () => new Response(), fetchNative: async () => new Response(),
      };
      const factory = new MediaDOM.PlayerDomFactory(host, {
        type: 'm3u8', url: 'https://cdn.test/one.m3u8', path: '/chapter/1', progress: 0,
        poster: 'https://cdn.test/poster.jpg', thumbnailsVtt: 'https://cdn.test/thumbs.vtt',
      }, { onNext() {}, onDismissNext() {} });
      const player = await factory.build('hlsjs-video', false);
      const playerContainer = factory.createContainer();
      root.append(player.root);
      const debug = new MediaDebug.PlayerDebugOverlay(document, () => undefined);
      debug.attach(false);
      debug.set(true);
      const skin = player.root.querySelector('video-skin');
      const shadow = skin.shadowRoot;
      const media = player.media;
      media.currentTime = 20;
      media.duration = 100;
      const skipButton = shadow.querySelector('.nekori-skip-intro');
      skipButton.click();
      return {
        rootTag: player.root.localName,
        playerContainerId: playerContainer.id,
        debugOverlayId: document.querySelector('#nekori-debug-overlay')?.id,
        debugToggleId: document.querySelector('#nekori-debug-toggle')?.id,
        playerTag: player.root.firstElementChild.localName,
        skinTag: skin.localName,
        mediaTag: media.localName,
        crossOrigin: media.getAttribute('crossorigin'),
        thumbKind: media.querySelector('track')?.kind,
        thumbLabel: media.querySelector('track')?.label,
        posterSlot: skin.querySelector('img')?.slot,
        posterSource: skin.querySelector('img')?.getAttribute('src'),
        overlayClass: shadow.querySelector('.nekori-overlay')?.className,
        nextUpClass: shadow.querySelector('.nekori-nextup')?.className,
        castButtons: shadow.querySelectorAll('media-cast-button').length,
        pipButtons: shadow.querySelectorAll('media-pip-button').length,
        tooltipCount: shadow.querySelectorAll('media-tooltip').length,
        pipAction: shadow.querySelector('media-status-indicator')?.getAttribute('actions'),
        skipTag: skipButton?.localName,
        skipLabel: skipButton?.getAttribute('aria-label'),
        skipTime: media.currentTime,
        vendorSeekDefined: customElements.get('media-seek-button') !== undefined,
        overlayPosition: getComputedStyle(shadow.querySelector('.nekori-overlay')).position,
        crossOriginCssBlocked: (() => {
          try { return document.styleSheets[0].cssRules.length === 0; } catch (error) { return error.name === 'SecurityError'; }
        })(),
        i18nParent: player.root.parentElement?.id ?? null,
        controlStyleInShadow: Boolean(shadow.querySelector('style')),
      };
    });
    assert.deepEqual(result, {
      rootTag: 'media-i18n', playerContainerId: 'nekori-player-container', debugOverlayId: 'nekori-debug-overlay', debugToggleId: 'nekori-debug-toggle', playerTag: 'video-player', skinTag: 'video-skin', mediaTag: 'hlsjs-video',
      crossOrigin: 'anonymous', thumbKind: 'metadata', thumbLabel: 'thumbnails', posterSlot: 'poster',
      posterSource: 'https://cdn.test/poster.jpg', overlayClass: 'nekori-overlay', nextUpClass: 'nekori-nextup media-popup-surface', castButtons: 0, pipButtons: 0, tooltipCount: 0,
      pipAction: 'play', skipTag: 'button', skipLabel: 'Skip intro', skipTime: 100, vendorSeekDefined: false,
      overlayPosition: 'absolute', crossOriginCssBlocked: true, i18nParent: 'root', controlStyleInShadow: true,
    });
    await page.close();
  } finally {
    await browser.close();
  }
});

browserTest(test, 'plain video does not opt into CORS unless thumbnail tracks are configured', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.setContent('<body><main id="root"></main></body>');
    await page.addStyleTag({ content: await mediaCss });
    await page.addScriptTag({ content: (await mediaBundle).outputFiles[0].text });
    const hasCrossOrigin = await page.evaluate(async () => {
      class Skin extends HTMLElement { constructor() { super(); this.attachShadow({ mode: 'open' }).innerHTML = '<media-container></media-container>'; } }
      for (const tag of ['video-player', 'video-skin', 'media-i18n', 'media-container']) customElements.define(tag, tag === 'video-skin' ? Skin : class extends HTMLElement {});
      const host = { root: document.querySelector('#root'), strings: {}, nextChapter: null, emit() {}, request: async () => true, fetch: async () => new Response(), fetchNative: async () => new Response() };
      const factory = new MediaDOM.PlayerDomFactory(host, { type: 'video-file', url: '/movie.mp4', path: '/ch', progress: 0 }, { onNext() {}, onDismissNext() {} });
      const player = await factory.build('video', false);
      return player.media.hasAttribute('crossorigin');
    });
    assert.equal(hasCrossOrigin, false);
    await page.close();
  } finally {
    await browser.close();
  }
});
