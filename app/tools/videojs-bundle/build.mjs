import { build, transform } from "esbuild";
import { gzipSync } from "node:zlib";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { pipFeature, remotePlaybackFeature } from "./src/no-remote-feature.js";

const toolDir = path.dirname(fileURLToPath(import.meta.url));
const assetDir = path.resolve(toolDir, "../../src/main/assets/novel-reader");
const sourceDir = path.join(toolDir, "src");
const checkOnly = process.argv.includes("--check");

await mkdir(assetDir, { recursive: true });

const hlsGlobal = path.join(sourceDir, "hls-global.js");
const noAirPlay = path.join(sourceDir, "hls-no-airplay.js");
const hlsErrors = path.join(sourceDir, "hls-errors.js");
const noRemoteFeature = path.join(sourceDir, "no-remote-feature.js");
const noRemoteElements = path.join(sourceDir, "no-remote-elements.js");
const noPipHotkeys = path.join(sourceDir, "no-pip-hotkeys.js");
for (const feature of [pipFeature, remotePlaybackFeature]) {
  if (typeof feature.state !== "function") {
    throw new Error(`Disabled feature ${feature.name} must retain the PlayerFeature shape`);
  }
}
const adapterOverrides = {
  name: "tsundoku-videojs-adapter-overrides",
  setup(buildApi) {
    buildApi.onResolve({ filter: /airplay-bridge\.js$/ }, (args) => {
      if (args.importer.replaceAll("\\", "/").includes("@videojs/media")) return { path: noAirPlay };
      return null;
    });
    buildApi.onResolve({ filter: /errors\.js$/ }, (args) => {
      const importer = args.importer.replaceAll("\\", "/");
      if (importer.includes("@videojs/media") && importer.includes("hls-js")) {
        return { path: hlsErrors };
      }
      return null;
    });
    buildApi.onResolve({ filter: /remote-playback\.js$/ }, (args) => {
      if (args.importer.replaceAll("\\", "/").includes("@videojs/core")) return { path: noRemoteFeature };
      return null;
    });
    buildApi.onResolve({ filter: /pip\.js$/ }, (args) => {
      const importer = args.importer.replaceAll("\\", "/");
      if (importer.includes("@videojs/core") && importer.includes("/dom/")) {
        return { path: noRemoteFeature };
      }
      return null;
    });
    buildApi.onResolve({ filter: /(airplay|cast|pip)-button-element\.js$/ }, (args) => {
      if (args.importer.replaceAll("\\", "/").includes("@videojs/html")) {
        return { path: noRemoteElements };
      }
      return null;
    });
    buildApi.onResolve({ filter: /actions\.js$/ }, (args) => {
      const importer = args.importer.replaceAll("\\", "/");
      if (
        !args.path.includes("media-actions") &&
        importer.includes("@videojs/core") &&
        (args.path.replaceAll("\\", "/").includes("hotkey/actions") || importer.includes("/dom/hotkey/"))
      ) {
        return { path: noPipHotkeys };
      }
      return null;
    });
  },
};

const videoResult = await build({
  entryPoints: [path.join(sourceDir, "player.js")],
  bundle: true,
  minify: true,
  legalComments: "none",
  format: "iife",
  platform: "browser",
  target: "es2022",
  outfile: path.join(assetDir, "videojs.min.js"),
  metafile: true,
  alias: { "hls.js": hlsGlobal },
  plugins: [adapterOverrides],
  write: !checkOnly,
  banner: {
    js: "/* eslint-disable */\n",
  },
});

const bundledInputs = new Map();
for (const output of Object.values(videoResult.metafile.outputs)) {
  for (const [input, details] of Object.entries(output.inputs ?? {})) {
    bundledInputs.set(input.replaceAll("\\", "/"), (bundledInputs.get(input) ?? 0) + details.bytesInOutput);
  }
}
const forbiddenInputs = [...bundledInputs].filter(
  ([input, bytes]) =>
    bytes > 0 && /node_modules\/hls\.js\/|google-cast|airplay-button|cast-button|pip-button|remote-playback/.test(input),
);
if (forbiddenInputs.length) {
  throw new Error(`Forbidden Video.js bundle code:\n${forbiddenInputs.map(([input]) => input).join("\n")}`);
}

const playerOutput = checkOnly
  ? videoResult.outputFiles.find((output) => output.path.endsWith(".js"))?.text ?? ""
  : await readFile(path.join(assetDir, "videojs.min.js"), "utf8");
if (playerOutput.includes("toggleRemotePlayback")) throw new Error("Forbidden player symbol: toggleRemotePlayback");
// A Tsundoku-only global or tag name would only exist in this bundle, so core-player.js could no longer
// run against a CDN build of the same version. Keep the bundle a drop-in for @videojs/html's own presets.
for (const forbiddenSymbol of ["TsundokuVideoJS", "tsundoku-video-player", "tsundoku-live-video-player"]) {
  if (playerOutput.includes(forbiddenSymbol)) {
    throw new Error(`Forbidden Tsundoku-specific player API: ${forbiddenSymbol}`);
  }
}
for (const requiredSymbol of [
  "video-player",
  "live-video-player",
  "video-skin",
  "live-video-skin",
  "hlsjs-video",
  "dash-video",
  "media-live-button",
  "media-fullscreen-button",
  "media-error-dialog",
  "media-default-skin",
  "media-poster",
  "media-slider-thumbnail",
  "media-hotkey",
  // Not part of the default video skin; core-player.js needs it for the intro/outro skip button.
  "media-seek-button",
  "media-quality-radio-group",
  "media-audio-track-radio-group",
  "media-captions-radio-group",
  // Without the provider element every media-text falls back to DEFAULT_LOCALE, so i18n dies silently.
  "media-i18n",
  "media-text",
]) {
  if (!playerOutput.includes(requiredSymbol)) throw new Error(`Missing player feature: ${requiredSymbol}`);
}

// The skin drags every locale's translation table in whether or not it is registered, so presence of the
// strings proves nothing. Check a non-English translation is reachable and that the registration loop ran.
{
  const localeDir = path.resolve(toolDir, "node_modules/@videojs/core/dist/default/i18n/locales");
  const sample = await readFile(path.join(localeDir, "vi.js"), "utf8");
  const phrase = sample.match(/pause:\s*"([^"]*)"/)?.[1];
  if (!phrase) throw new Error("Cannot read a sample translation to verify i18n");
  // esbuild emits charset=ascii, so non-ASCII becomes \xNN below U+0100 and \uNNNN above it.
  const escaped = [...phrase]
    .map((char) => {
      const code = char.charCodeAt(0);
      if (code <= 0x7f) return char;
      const hex = code.toString(16).toUpperCase();
      return code <= 0xff ? `\\x${hex.padStart(2, "0")}` : `\\u${hex.padStart(4, "0")}`;
    })
    .join("");
  if (!playerOutput.includes(phrase) && !playerOutput.includes(escaped)) {
    throw new Error("Missing bundled translations: locale packs were tree-shaken");
  }
  if (!playerOutput.includes("localeTags") && !/for\s*\(.{0,80}Object\.entries/.test(playerOutput)) {
    throw new Error("Locale packs are bundled but never registered: import locales/all/register");
  }
}
if (playerOutput.includes("media-minimal-skin")) throw new Error("Unexpected minimal skin markup");

const hlsPackage = JSON.parse(await readFile(path.resolve(toolDir, "node_modules/hls.js/package.json"), "utf8"));
if (hlsPackage.version !== "1.6.15") throw new Error(`Expected hls.js 1.6.15, found ${hlsPackage.version}`);
const videoPackage = JSON.parse(
  await readFile(path.resolve(toolDir, "node_modules/@videojs/html/package.json"), "utf8"),
);
if (videoPackage.version !== "10.0.0-beta.26") {
  throw new Error(`Expected @videojs/html 10.0.0-beta.26, found ${videoPackage.version}`);
}

let hlsSource = await readFile(path.resolve(toolDir, "node_modules/hls.js/dist/hls.js"), "utf8");
const captureAnchor = `      if (discontinuity || trackSwitch || initSegmentChange || resetMuxers) {\n`;
const capturePatch = `      if (!keyData && this.config.tsundokuCaptureFragments) {\n        var offset = this.demuxer instanceof TSDemuxer ? Math.max(0, TSDemuxer.syncOffset(uintData)) : 0;\n        var end = this.demuxer instanceof TSDemuxer ? uintData.length - (uintData.length - offset) % 188 : uintData.length;\n        var payload = uintData.subarray(offset, end);\n        this.observer.emit(Events.FRAG_DECRYPTED, Events.FRAG_DECRYPTED, {\n          frag: undefined,\n          payload: payload.byteOffset === 0 && payload.byteLength === payload.buffer.byteLength ? payload.buffer : payload.slice().buffer,\n          stats: { tstart: stats.executeStart, tdecrypt: now() }\n        });\n      }\n`;
if (!hlsSource.includes(captureAnchor)) throw new Error("hls.js capture patch anchor changed");
hlsSource = hlsSource.replace(captureAnchor, capturePatch + captureAnchor);

// A download writes one file, so it needs one moov covering both tracks; hls.js only builds the
// per-SourceBuffer init segments MSE wants. The field hangs off tracks.video rather than off tracks
// itself because BufferController and StreamController both iterate Object.keys(tracks) and would
// treat a sibling key as a SourceBuffer name.
const initAnchor = `            width: videoTrack.width,\n            height: videoTrack.height\n          }\n        };\n`;
// The 'audio/mpeg' container is mp3 that hls.js passes through as raw MPEG frames with an empty
// moof, so its output is not MP4 at all. Withholding the combined init keeps "this field exists" the
// single test for "the remuxer output is a usable file".
const initPatch = `        if (this.config.tsundokuCaptureFragments && tracks.audio && tracks.audio.container !== 'audio/mpeg') {\n          tracks.video.tsundokuInitSegment = MP4.initSegment([videoTrack, audioTrack]);\n        }\n`;
if (!hlsSource.includes(initAnchor)) throw new Error("hls.js combined init patch anchor changed");
hlsSource = hlsSource.replace(initAnchor, initAnchor + initPatch);

const workerAnchor = `        var observer = new EventEmitter();\n        observer.on(Events.FRAG_DECRYPTED, forwardMessage);\n        observer.on(Events.ERROR, forwardMessage);\n`;
const workerPatch = `        var observer = new EventEmitter();\n        var forwardObserverMessage = function forwardObserverMessage(event, data) {\n          return forwardMessage(event, data, instanceNo);\n        };\n        observer.on(Events.FRAG_DECRYPTED, forwardObserverMessage);\n        observer.on(Events.ERROR, forwardObserverMessage);\n`;
if (!hlsSource.includes(workerAnchor)) throw new Error("hls.js worker patch anchor changed");
hlsSource = hlsSource.replace(workerAnchor, workerPatch);

const hlsResult = await transform(hlsSource, {
  minify: true,
  legalComments: "none",
  target: "es2020",
});
for (const requiredPatch of ["tsundokuCaptureFragments", "tsundokuInitSegment"]) {
  if (!hlsResult.code.includes(requiredPatch)) throw new Error(`Missing hls.js download patch: ${requiredPatch}`);
  if (playerOutput.includes(requiredPatch)) throw new Error(`Download patch leaked into Video.js bundle: ${requiredPatch}`);
}
// A sibling key on the TrackSet would make BufferController create a SourceBuffer named after it,
// throw from addSourceBuffer and hang the download. Only a member of the video track is safe.
if (!/\.video\.tsundokuInitSegment\s*=/.test(hlsResult.code)) {
  throw new Error("Combined init segment must be assigned onto tracks.video, not onto the track set");
}
if (!checkOnly) await writeFile(path.join(assetDir, "hls.min.js"), hlsResult.code);

async function sizeLine(name, bytes) {
  return `${name}: ${bytes.length} bytes raw, ${gzipSync(bytes).length} bytes gzip`;
}

const videoBytes = checkOnly
  ? Buffer.from(videoResult.outputFiles.find((output) => output.path.endsWith(".js"))?.contents ?? [])
  : await readFile(path.join(assetDir, "videojs.min.js"));
const hlsBytes = checkOnly ? Buffer.from(hlsResult.code) : await readFile(path.join(assetDir, "hls.min.js"));

console.log(await sizeLine("videojs.min.js", videoBytes));
console.log(await sizeLine("hls.min.js", hlsBytes));

// The bundle must emit no stylesheet. Upstream's baseline sheet is inlined in the JS and sets
// `display: contents` on the player elements; reader-side sizing lives in core-player.css. A CSS asset
// here would be a third source of layout rules fighting both.
if (videoResult.outputFiles?.some((output) => output.path.endsWith(".css"))) {
  throw new Error("Unexpected esbuild CSS output: reader layout belongs to core-player.css");
}
for (const staleAsset of ["videojs.min.css", "player.css"]) {
  try {
    await stat(path.join(assetDir, staleAsset));
    throw new Error(`Stale generated asset still present: ${staleAsset}`);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
}
