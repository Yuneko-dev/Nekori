import assert from "node:assert/strict";
import { checkOverlays } from "./overlay-test.mjs";
import { createServer } from "node:http";
import { mkdtemp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

// Use an existing Playwright installation; no browser dependency is shipped with the app.
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : "playwright");
const fixtures = await mkdtemp(path.join(tmpdir(), "nekori-player-"));
const assets = new URL("../../src/main/assets/novel-reader/", import.meta.url);
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: "utf8", cwd: fixtures });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout;
};
const browser = await chromium.launch({ channel: "chrome", headless: true });
const server = createServer(async (request, response) => {
  try {
    const name = new URL(request.url, "http://localhost").pathname.slice(1);
    if (name === "favicon.ico") { response.writeHead(204).end(); return; }
    if (!name) {
      response.setHeader("Content-Type", "text/html");
      response.end('<html lang="vi"><link rel="stylesheet" href="core-player.css"><div id="LNReader-chapter"></div><script>window.reader={error:console.error,strings:{videoResumeContinue:"Continue",videoResumeRestart:"Restart"},nextChapter:{name:"Next"}}</script><script src="hls.min.js"></script><script src="videojs.min.js"></script><script src="core-player.js"></script>');
    } else {
      response.setHeader("Content-Type", name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "application/octet-stream");
      response.end(await readFile(/\.(js|css)$/.test(name) ? new URL(name, assets) : path.join(fixtures, name)));
    }
  } catch { response.writeHead(404).end(); }
});
try {
  run("ffmpeg", ["-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "8", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "48", "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", path.join(fixtures, "stream.m3u8")]);
  await writeFile(path.join(fixtures, "key"), Buffer.alloc(16, 7));
  await writeFile(path.join(fixtures, "key-info"), `/key
${path.join(fixtures, "key")}
`);
  for (const [name, options] of [["encrypted", ["-c", "copy", "-hls_key_info_file", path.join(fixtures, "key-info")]], ["mp3", ["-c:v", "copy", "-c:a", "libmp3lame"]]]) {
    run("ffmpeg", ["-v", "error", "-i", path.join(fixtures, "stream.m3u8"), ...options, "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", path.join(fixtures, name + ".m3u8")]);
  }
  for (const [name, format] of [["direct.mp4", "mp4"], ["manifest.mpd", "dash"]]) {
    run("ffmpeg", ["-v", "error", "-i", path.join(fixtures, "stream.m3u8"), "-c", "copy", "-tag:v", "avc1", "-tag:a", "mp4a", "-bsf:a", "aac_adtstoasc", "-f", format, path.join(fixtures, name)]);
  }
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const page = await browser.newPage();
  const errors = [];
  page.on("console", message => { if (message.type() === "error") console.error(message.text()); });
  page.on("pageerror", error => errors.push(error.stack || `${error.name}: ${error.message}`));
  await page.goto(origin);
  await page.evaluate(() => LNReaderPlayer.playHls("/stream.m3u8", { maxBufferLength: 17, tsundokuCaptureFragments: true }));
  await page.waitForFunction(() => LNReaderPlayer.videoElement?.currentTime > 0);
  assert.deepEqual(await page.evaluate(() => {
    const media = LNReaderPlayer.videoElement;
    const shadow = document.querySelector("video-skin").shadowRoot;
    return {
      version: Hls.version, buffer: media.engine.config.maxBufferLength,
      capture: media.engine.config.tsundokuCaptureFragments,
      skip: !!shadow.querySelector("media-seek-button"),
      forbidden: shadow.querySelectorAll("media-pip-button,media-airplay-button,media-cast-button").length,
      theme: shadow.querySelector("media-container").dataset.theme,
      translated: shadow.querySelector("media-fullscreen-button").getAttribute("aria-label"),
    };
  }), { version: "1.7.3", buffer: 17, capture: false, skip: true, forbidden: 0, theme: "default", translated: "Toàn màn hình" });
  await page.locator("media-container").click({ position: { x: 20, y: 20 } });
  await page.keyboard.press("m");
  await page.waitForFunction(() => LNReaderPlayer.videoElement.muted);
  await page.evaluate(() => {
    const media = LNReaderPlayer.videoElement;
    media.pause();
    LNReaderPlayer.offerResume(media, 2);
  });
  await page.locator('[data-action="continue"]').click();
  await page.waitForFunction(() => LNReaderPlayer.videoElement.currentTime >= 2 && !LNReaderPlayer.awaitingResume);
  assert.equal(await page.evaluate(() => LNReaderPlayer.videoElement.videoRenditions.length), 1);
  console.log("PASS HLS playback, config, skin, skip, i18n, quality, hotkey, resume, disabled controls");
  await checkOverlays(page);
  for (const kind of ["direct", "dash", "live"]) {
    await page.evaluate(async kind => {
      LNReaderPlayer.disableProgress = kind === "live";
      if (kind === "direct") await LNReaderPlayer.playDirect("/direct.mp4");
      else if (kind === "dash") await LNReaderPlayer.playDash(location.origin + "/manifest.mpd", { settings: { streaming: { buffer: { bufferTimeDefault: 19 } } } });
      else await LNReaderPlayer.playHls("/stream.m3u8");
    }, kind);
    await page.waitForFunction(() => LNReaderPlayer.videoElement?.currentTime > 0).catch(async error => {
      console.error(await page.evaluate(() => ({ source: LNReaderPlayer.videoElement?.source, error: LNReaderPlayer.videoElement?.error?.message, ready: LNReaderPlayer.videoElement?.readyState, paused: LNReaderPlayer.videoElement?.paused })));
      throw error;
    });
    if (kind === "dash") assert.equal(await page.evaluate(() => LNReaderPlayer.videoElement.engine.getSettings().streaming.buffer.bufferTimeDefault), 19);
    assert.equal(await page.locator("media-seek-button").count(), 1);
    console.log(`PASS ${kind} playback + skip control`);
  }
  for (const type of ["networkError", "mediaError"]) {
    await page.evaluate(() => LNReaderPlayer.playHls("/stream.m3u8"));
    await page.waitForFunction(() => LNReaderPlayer.videoElement?.currentTime > 0);
    const recovery = await page.evaluate(type => {
      const media = LNReaderPlayer.videoElement;
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
    const result = await page.evaluate(async ({ name, enableWorker }) => {
      LNReaderPlayer.destroyCurrentMedia();
      const chunks = [];
      let container;
      LNReaderPlayer.readyDownload = async value => { container = value; };
      LNReaderPlayer.putDownloadChunk = async bytes => chunks.push(...bytes);
      LNReaderPlayer.commitDownload = async () => {};
      await LNReaderPlayer.downloadHls(`/${name}.m3u8`, { enableWorker });
      return { container, chunks };
    }, { name, enableWorker });
    assert.equal(result.container, name === "mp3" ? "ts" : "mp4");
    const output = path.join(fixtures, `download-${name}-${enableWorker}.${result.container}`);
    await writeFile(output, Buffer.from(result.chunks));
    const probe = JSON.parse(run("ffprobe", ["-v", "error", "-show_streams", "-of", "json", output]));
    assert.deepEqual(probe.streams.map(stream => stream.codec_type).sort(), ["audio", "video"]);
    run("ffmpeg", ["-v", "error", "-xerror", "-i", output, "-f", "null", "-"]);
    console.log(`PASS ${name} download + decode (worker=${enableWorker})`);
  }
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  server.close();
  await rm(fixtures, { recursive: true, force: true });
}
