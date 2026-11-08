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
      contents: await readFile(args.path, 'utf8'), loader: 'text',
    }));
  },
};
const playerBundle = build({
  absWorkingDir: fileURLToPath(new URL('.', import.meta.url)),
  entryPoints: ['../src/media/player-controller.ts'],
  bundle: true,
  format: 'iife',
  globalName: 'MediaPlayer',
  platform: 'browser',
  target: 'chrome138',
  plugins: [inlineCssPlugin],
  write: false,
});

browserTest(test, 'disposing a player suppresses late element, DRM, and failure work', async () => {
  const browser = await chromium.launch({ executablePath: chromiumPath });
  try {
    const page = await browser.newPage();
    await page.route('https://reader.test/**', (route) => route.fulfill({
      status: 200, contentType: 'text/html', body: '<body><main id="root"></main></body>',
    }));
    await page.goto('https://reader.test/');
    await page.addScriptTag({ content: (await playerBundle).outputFiles[0].text });
    await page.evaluate(() => {
      const originalCreate = document.createElement.bind(document);
      document.createElement = (name, options) => {
        const element = originalCreate(name, options);
        if (name === 'video') window.createdVideo = element;
        if (name === 'dash-video') window.createdDash = element;
        return element;
      };
      const registry = customElements;
      const whenDefined = registry.whenDefined.bind(registry);
      window.pendingDefinitions = [];
      registry.whenDefined = (name) => {
        window.pendingDefinitions.push(name);
        return whenDefined(name);
      };
      window.playerEvents = [];
      window.host = {
        root: document.querySelector('#root'),
        chapter: { id: 'chapter-id', path: '/opaque/chapter', progress: 0 },
        nextChapter: null,
        strings: {},
        fetch: async () => { window.fetchCalls = (window.fetchCalls || 0) + 1; return new Response(''); },
        fetchNative: async () => new Response(null, { status: 200 }),
        emit: (name, payload) => window.playerEvents.push([name, payload]),
        request: async () => true,
      };
      localStorage.setItem('__tsundoku_video_fraction', '{"kept":0.5}');
      window.controller = MediaPlayer.createPlayer(window.host, {
        mode: 'playback',
        video: { type: 'video-file', path: '/opaque/chapter', progress: 0 },
      });
      window.playPending = window.controller.playDirect('https://late.test/video.mp4');
    });
    await page.waitForFunction(() => window.pendingDefinitions?.includes('video-skin'));
    assert.equal(await page.locator('#nekori-player-container').count(), 1);
    await page.evaluate(() => window.controller.dispose());
    await page.evaluate(() => {
      customElements.define('video-player', class extends HTMLElement {});
      customElements.define('media-i18n', class extends HTMLElement {});
      customElements.define('video-skin', class extends HTMLElement {
        constructor() {
          super();
          this.attachShadow({ mode: 'open' }).innerHTML = '<media-container><div class="video-controls-primary"></div></media-container>';
        }
      });
      customElements.define('dash-video', class extends HTMLElement {
        constructor() {
          super();
          this.src = '';
          this.engine = { setProtectionData() {}, on() {}, events: { ERROR: 'error' } };
        }
      });
    });
    await page.evaluate(() => window.playPending);
    await page.evaluate(() => {
      const media = window.createdVideo;
      if (media) {
        Object.defineProperty(media, 'currentTime', { configurable: true, value: 25, writable: true });
        Object.defineProperty(media, 'duration', { configurable: true, value: 100 });
        media.dispatchEvent(new Event('timeupdate'));
      }
    });
    assert.deepEqual(await page.evaluate(() => ({
      container: document.querySelector('#nekori-player-container'),
      debugOverlay: document.querySelector('#nekori-debug-overlay'),
      videoConnected: Boolean(window.createdVideo?.isConnected),
      videoSource: window.createdVideo?.getAttribute('src') ?? null,
      fraction: localStorage.getItem('__tsundoku_video_fraction'),
      events: window.playerEvents,
      fetchCalls: window.fetchCalls || 0,
    })), {
      container: null, debugOverlay: null, videoConnected: false, videoSource: null,
      fraction: '{"kept":0.5}', events: [], fetchCalls: 0,
    });

    await page.evaluate(async () => {
      let releaseRequest;
      window.requestStarted = false;
      window.host.request = () => {
        window.requestStarted = true;
        return new Promise((resolve) => { releaseRequest = resolve; });
      };
      window.releaseRequest = (value) => releaseRequest(value);
      window.grantedController = MediaPlayer.createPlayer(window.host, {
        mode: 'playback', video: { type: 'mpd', path: '/opaque/chapter', progress: 0 },
      });
      window.grantedPlay = window.grantedController.playDash('https://late.test/manifest.mpd', {
        protectionData: { 'com.widevine.alpha': { serverURL: 'https://license.test' } },
      });
    });
    await page.waitForFunction(() => window.requestStarted);
    await page.evaluate(() => {
      window.grantedController.dispose();
      window.releaseRequest(true);
    });
    await page.evaluate(() => window.grantedPlay);
    assert.equal(await page.evaluate(() => window.createdDash.src), '');

    await page.evaluate(async () => {
      let releaseRequest;
      window.requestStarted = false;
      window.host.request = () => {
        window.requestStarted = true;
        return new Promise((resolve) => { releaseRequest = resolve; });
      };
      window.releaseRequest = (value) => releaseRequest(value);
      window.deniedController = MediaPlayer.createPlayer(window.host, {
        mode: 'playback', video: { type: 'mpd', path: '/opaque/chapter', progress: 0 },
      });
      window.deniedPlay = window.deniedController.playDash('https://late.test/manifest.mpd', {
        protectionData: { 'com.widevine.alpha': { serverURL: 'https://license.test' } },
      });
    });
    await page.waitForFunction(() => window.requestStarted);
    await page.evaluate(() => {
      window.deniedController.dispose();
      window.releaseRequest(false);
    });
    await page.evaluate(() => window.deniedPlay);
    assert.deepEqual(await page.evaluate(() => window.playerEvents), []);
    await page.close();
  } finally {
    await browser.close();
  }
});
