import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import { checkOverlays } from "./overlay-test.mjs";

const playwrightPath = process.env.PLAYWRIGHT_MODULE;
const playwrightEntry = playwrightPath && !/\.(?:mjs|js)$/i.test(playwrightPath)
  ? path.join(playwrightPath, "index.mjs") : playwrightPath;
const { chromium } = await import(playwrightEntry
  ? pathToFileURL(playwrightEntry).href : "playwright");
const fixtures = await mkdtemp(path.join(tmpdir(), "nekori-player-"));
const assets = new URL("../../src/main/assets/novel-reader/", import.meta.url);
const runtime = new URL("../reader-runtime/dist/index.js", import.meta.url);
const downloadEntry = new URL("../reader-runtime/dist/assets/download.js", import.meta.url);
const runtimeModules = new URL("../reader-runtime/node_modules/", import.meta.url);
const sink = { container: "", chunks: [], complete: false };
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: "utf8", cwd: fixtures });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout;
};
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  channel: process.env.CHROMIUM_PATH ? undefined : "chrome",
  headless: true,
});
const readRequest = async (request) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks);
};
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, "http://localhost");
    const name = url.pathname.slice(1);
    if (name === "favicon.ico") { response.writeHead(204).end(); return; }
    if (name === "download-entry") {
      const config = {
        documentId: "headless-download-document", mode: "download",
        novel: { id: "novel-id", name: "Novel", path: "/novel" },
        chapter: { id: "chapter-id", name: "Current chapter", path: "/opaque//chapter#one", progress: 0 },
        nextChapter: null, strings: {}, autoSaveInterval: 5, chapters: [], proxyEndpoint: "/proxy",
        runtime: { editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false, menuVisible: true, immersive: false, ttsState: "stopped", loadingChapter: false, loadingNext: false, noMoreChapters: false },
        player: { mode: "download", downloadEndpoint: "/download", video: { type: "video-file", url: "/direct.mp4", path: "/opaque//chapter#one", progress: 0, direct: true } },
      };
      response.setHeader("Content-Type", "text/html; charset=utf-8");
      response.end(`<!doctype html><html><body><main id="nekori-reader"></main><script id="nekori-reader-config" type="application/json">${JSON.stringify(config)}</script><script>window.__mediaEvents=[];window.__nekoriNativeTransport={postMessage(message){const event=JSON.parse(message);if(event.kind==="event")window.__mediaEvents.push([event.method,event.payload]);},onmessage:null};</script><script src="/download.js"></script></body></html>`);
      return;
    }
    if (name === "download/sink") {
      if (request.method === "DELETE") { sink.container = ""; sink.chunks = []; sink.complete = false; }
      if (request.method === "POST" && url.searchParams.has("container")) {
        sink.container = url.searchParams.get("container"); sink.chunks = []; sink.complete = false;
      } else if (request.method === "POST") sink.complete = true;
      else if (request.method === "PUT") sink.chunks.push(await readRequest(request));
      response.writeHead(200).end(); return;
    }
    if (!name) {
      response.setHeader("Content-Type", "text/html; charset=utf-8");
      response.end(`<!doctype html><html lang="vi"><head><link rel="stylesheet" href="/player.css"><script type="importmap">{"imports":{"vanjs-core":"/vanjs-core.js"}}</script></head><body><main id="nekori-reader"></main><script src="/hls.min.js"></script><script src="/videojs.min.js"></script><script type="module">
        import { createReader, createRuntimeHost } from "/runtime.js";
        const root = document.querySelector("#nekori-reader");
        let api;
        let events = [];
        window.mediaHarness = {
          async setPlayer({ mode = "playback", type = "m3u8", url = "/stream.m3u8", progress = 0, language = "vi", next = true } = {}) {
            api?.dispose();
            events = [];
            const chapter = { id: "chapter-id", name: "Current chapter", path: "/opaque//chapter#one", progress };
            const nextChapter = next ? { id: "next-id", name: "Next chapter", path: "/opaque//chapter-two", progress: 0 } : null;
            const strings = language === "vi" ? {
                videoResumeTitle: "Hệ thống ghi nhận bạn đã từng xem video này trước đó!",
                videoResumeQuestion: "Bạn có muốn xem tiếp từ đoạn", videoResumeContinue: "Xem tiếp",
                videoResumeRestart: "Từ đầu", videoNextUp: "Tập tiếp theo", videoNextPlay: "Xem ngay",
                videoSkipIntro: "Bỏ qua mở đầu", close: "Đóng",
              } : {
                videoResumeTitle: "You watched this video before", videoResumeQuestion: "Continue from",
                videoResumeContinue: "Continue", videoResumeRestart: "From the beginning",
                videoNextUp: "Up next", videoNextPlay: "Play now", videoSkipIntro: "Skip intro", close: "Close",
              };
            const config = {
              documentId: "test-document", mode: mode === "download" ? "download" : "reader",
              novel: { id: "novel-id", name: "Novel", path: "/novel" }, chapter, nextChapter,
              strings, autoSaveInterval: 5, chapters: [chapter, ...(nextChapter ? [nextChapter] : [])],
              runtime: { editing: false, infinite: false, textSelectionBlocked: false, forcedLowercase: false, menuVisible: true, immersive: false, ttsState: "stopped", loadingChapter: false, loadingNext: false, noMoreChapters: false },
              proxyEndpoint: "/proxy",
              player: { mode, video: { type, url, path: chapter.path, progress, disableProgress: type === "live", debug: false }, downloadEndpoint: "/download" },
            };
            const host = createRuntimeHost({ root, config, transport: {
              postMessage(message) {
                const envelope = JSON.parse(message);
                if (envelope.kind === "event") events.push([envelope.method, envelope.payload]);
              }, onmessage: null,
            } });
            api = createReader({ root, host, config });
            await api.ready;
            return api.player;
          },
          get player() { return api?.player; },
          get events() { return events; },
          clearEvents() { events.length = 0; },
        };
        window.runtimeReady = true;
      </script></body></html>`);
      return;
    }
    if (name === "runtime.js") {
      response.setHeader("Content-Type", "text/javascript"); response.end(await readFile(runtime)); return;
    }
    if (name === "download.js") {
      response.setHeader("Content-Type", "text/javascript"); response.end(await readFile(downloadEntry)); return;
    }
    if (name === "vanjs-core.js") {
      response.setHeader("Content-Type", "text/javascript");
      response.end(await readFile(new URL("vanjs-core/src/van.js", runtimeModules))); return;
    }
    if (name === "player.css") {
      response.setHeader("Content-Type", "text/css");
      response.end(await readFile(new URL("../reader-runtime/src/styles/player.css", import.meta.url))); return;
    }
    if (name === "proxy") {
      const sourceUrl = new URL(url.searchParams.get("url") || "", `http://127.0.0.1:${server.address().port}`);
      response.setHeader("Content-Type", sourceUrl.pathname.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "application/octet-stream");
      response.end(await readFile(path.join(fixtures, sourceUrl.pathname.slice(1)))); return;
    }
    if (name === "videojs.min.js" || name === "hls.min.js") {
      response.setHeader("Content-Type", "text/javascript");
      response.end(await readFile(new URL(name, assets))); return;
    }
    response.setHeader("Content-Type", name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "application/octet-stream");
    response.end(await readFile(path.join(fixtures, name)));
  } catch { response.writeHead(404).end(); }
});

try {
  run("ffmpeg", ["-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "8", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "48", "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", path.join(fixtures, "stream.m3u8")]);
  await writeFile(path.join(fixtures, "key"), Buffer.alloc(16, 7));
  await writeFile(path.join(fixtures, "key-info"), `/key\n${path.join(fixtures, "key")}\n`);
  for (const [name, options] of [["encrypted", ["-c", "copy", "-hls_key_info_file", path.join(fixtures, "key-info")]], ["mp3", ["-c:v", "copy", "-c:a", "libmp3lame"]]]) {
    run("ffmpeg", ["-v", "error", "-i", path.join(fixtures, "stream.m3u8"), ...options, "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", path.join(fixtures, name + ".m3u8")]);
  }
  for (const [name, format] of [["direct.mp4", "mp4"], ["manifest.mpd", "dash"]]) {
    run("ffmpeg", ["-v", "error", "-i", path.join(fixtures, "stream.m3u8"), "-c", "copy", "-tag:v", "avc1", "-tag:a", "mp4a", "-bsf:a", "aac_adtstoasc", "-f", format, path.join(fixtures, name)]);
  }
  run("ffmpeg", ["-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "130", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "35", "-g", "48", "-c:a", "aac", "-b:a", "32k", "-movflags", "+faststart", path.join(fixtures, "direct-long.mp4")]);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const page = await browser.newPage();
  const errors = [];
  page.on("console", message => { if (message.type() === "error") console.error(message.text()); });
  page.on("pageerror", error => { errors.push(error.stack || `${error.name}: ${error.message}`); console.error(error.stack || error.message); });
  page.on("response", response => { if (response.status() >= 400) console.error(`HTTP ${response.status()} ${response.url()}`); });
  await page.goto(origin);
  await page.waitForFunction(() => window.runtimeReady);
  await page.evaluate(async () => {
    const player = await mediaHarness.setPlayer({});
    await player.playHls("/stream.m3u8", { maxBufferLength: 17, tsundokuCaptureFragments: true });
  });
  await page.waitForFunction(() => document.querySelector("hlsjs-video")?.currentTime > 0);
  assert.deepEqual(await page.evaluate(() => {
    const media = document.querySelector("hlsjs-video");
    const shadow = document.querySelector("video-skin").shadowRoot;
    return {
      version: Hls.version, buffer: media.engine.config.maxBufferLength,
      capture: media.engine.config.tsundokuCaptureFragments,
      skip: !!shadow.querySelector(".nekori-skip-intro"),
      forbidden: shadow.querySelectorAll("media-pip-button,media-airplay-button,media-cast-button").length,
      theme: shadow.querySelector("media-container").dataset.theme,
      translated: shadow.querySelector("media-fullscreen-button").getAttribute("aria-label"),
    };
  }), { version: "1.7.3", buffer: 17, capture: false, skip: true, forbidden: 0, theme: "default", translated: "Toàn màn hình" });
  await page.locator("media-container").click({ force: true, position: { x: 20, y: 20 } });
  await page.keyboard.press("m");
  await page.waitForFunction(() => document.querySelector("hlsjs-video")?.muted);
  await page.evaluate(async () => {
    const player = await mediaHarness.setPlayer({ progress: 25 });
    await player.playHls("/stream.m3u8");
  });
  await page.waitForFunction(() => !document.querySelector(".nekori-overlay")?.hidden);
  await page.locator('[data-action="continue"]').click();
  await page.waitForFunction(() => document.querySelector("hlsjs-video")?.currentTime >= 2);
  assert.equal(await page.evaluate(() => document.querySelector("hlsjs-video").videoRenditions.length), 1);
  console.log("PASS HLS playback, config, skin, skip, i18n, quality, hotkey, resume, disabled controls");
  await checkOverlays(page);

  for (const kind of ["direct", "dash", "live"]) {
    await page.evaluate(async kind => {
      const type = kind === "direct" ? "video-file" : kind === "dash" ? "mpd" : "m3u8";
      const url = kind === "direct" ? "/direct.mp4" : kind === "dash" ? `${location.origin}/manifest.mpd` : "/stream.m3u8";
      const player = await mediaHarness.setPlayer({ type, url, progress: 0 });
      if (kind === "direct") await player.playDirect(url);
      else if (kind === "dash") await player.playDash(url, { settings: { streaming: { buffer: { bufferTimeDefault: 19 } } } });
      else await player.playHls(url);
    }, kind);
    await page.waitForFunction(() => document.querySelector("video, hlsjs-video, dash-video")?.currentTime > 0).catch(async error => {
      console.error(await page.evaluate(() => ({ source: document.querySelector("video, hlsjs-video, dash-video")?.src, error: document.querySelector("video, hlsjs-video, dash-video")?.error?.message, ready: document.querySelector("video, hlsjs-video, dash-video")?.readyState, paused: document.querySelector("video, hlsjs-video, dash-video")?.paused })));
      throw error;
    });
    if (kind === "dash") assert.equal(await page.evaluate(() => document.querySelector("dash-video").engine.getSettings().streaming.buffer.bufferTimeDefault), 19);
    assert.equal(await page.locator(".nekori-skip-intro").count(), 1);
    console.log(`PASS ${kind} playback + skip control`);
  }

  for (const type of ["networkError", "mediaError"]) {
    await page.evaluate(async () => {
      const player = await mediaHarness.setPlayer();
      await player.playHls("/stream.m3u8");
    });
    await page.waitForFunction(() => document.querySelector("hlsjs-video")?.currentTime > 0);
    const recovery = await page.evaluate(type => {
      const media = document.querySelector("hlsjs-video");
      const engine = media.engine;
      const method = type === "networkError" ? "startLoad" : "recoverMediaError";
      let count = 0;
      const original = engine[method].bind(engine);
      engine[method] = (...args) => { count++; return original(...args); };
      const error = { type, fatal: true, details: "regression-test", error: new Error("Expected test failure") };
      engine.trigger(Hls.Events.ERROR, error);
      const first = media.error;
      engine.trigger(Hls.Events.ERROR, error);
      return { count, first, terminal: media.error?.message };
    }, type);
    assert.deepEqual(recovery, { count: 1, first: null, terminal: "Expected test failure" });
    console.log(`PASS bounded ${type} recovery + terminal error`);
  }

  for (const name of ["stream", "encrypted", "mp3"]) for (const enableWorker of [false, true]) {
    await page.evaluate(async ({ name, enableWorker }) => {
      const player = await mediaHarness.setPlayer({ mode: "download", type: "m3u8", url: `/${name}.m3u8` });
      await player.playHls(`/${name}.m3u8`, { enableWorker });
    }, { name, enableWorker });
    await page.waitForFunction(() => mediaHarness.events.some(([event]) => event === "media.download.complete" || event === "media.download.error"));
    const events = await page.evaluate(() => mediaHarness.events);
    assert.ok(events.some(([event]) => event === "media.download.complete"), JSON.stringify(events));
    assert.equal(sink.container, name === "mp3" ? "ts" : "mp4");
    assert.equal(sink.complete, true);
    const output = path.join(fixtures, `download-${name}-${enableWorker}.${sink.container}`);
    const bytes = Buffer.concat(sink.chunks);
    assert.ok(bytes.length > 0);
    await writeFile(output, bytes);
    const probe = JSON.parse(run("ffprobe", ["-v", "error", "-show_streams", "-of", "json", output]));
    assert.deepEqual(probe.streams.map(stream => stream.codec_type).sort(), ["audio", "video"]);
    run("ffmpeg", ["-v", "error", "-xerror", "-i", output, "-f", "null", "-"]);
    console.log(`PASS ${name} download + decode (worker=${enableWorker})`);
  }

  await page.evaluate(async () => {
    const player = await mediaHarness.setPlayer({ mode: "download", type: "video-file", url: "/direct.mp4" });
    await player.playDirect("/direct.mp4");
  });
  await page.waitForFunction(() => mediaHarness.events.some(([event]) => event === "media.download.complete"));
  assert.equal(sink.container, "mp4");
  const directOutput = path.join(fixtures, "download-direct.mp4");
  await writeFile(directOutput, Buffer.concat(sink.chunks));
  assert.deepEqual(JSON.parse(run("ffprobe", ["-v", "error", "-show_streams", "-of", "json", directOutput])).streams.map(stream => stream.codec_type).sort(), ["audio", "video"]);
  run("ffmpeg", ["-v", "error", "-xerror", "-i", directOutput, "-f", "null", "-"]);
  console.log("PASS direct download + decode");

  const downloadPage = await browser.newPage();
  await downloadPage.goto(`${origin}/download-entry`);
  await downloadPage.waitForFunction(() => window.__mediaEvents?.some(([event]) => event === "media.download.complete" || event === "media.download.error"));
  const entryEvents = await downloadPage.evaluate(() => window.__mediaEvents);
  assert.ok(entryEvents.some(([event]) => event === "media.download.complete"), JSON.stringify(entryEvents));
  assert.equal(sink.container, "mp4");
  assert.equal(sink.complete, true);
  const entryOutput = path.join(fixtures, "download-entry.mp4");
  await writeFile(entryOutput, Buffer.concat(sink.chunks));
  assert.deepEqual(JSON.parse(run("ffprobe", ["-v", "error", "-show_streams", "-of", "json", entryOutput])).streams.map(stream => stream.codec_type).sort(), ["audio", "video"]);
  run("ffmpeg", ["-v", "error", "-xerror", "-i", entryOutput, "-f", "null", "-"]);
  await downloadPage.close();
  console.log("PASS generated headless download entry + native sink callback + decode");
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  server.close();
  await rm(fixtures, { recursive: true, force: true });
}
