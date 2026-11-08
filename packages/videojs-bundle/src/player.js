// Registers exactly the custom elements the Video.js CDN preset registers, and nothing else.
//
// Everything here is a side-effect import: each module calls safeDefine() with upstream's own tag
// name. Do not export a Tsundoku-specific façade or define Tsundoku-specific tag names — @nekori/reader
// must run against a CDN build of the same version with no code changes, so it uses only upstream's
// element names. Player composition and reader controls live in the typed @nekori/reader media modules.
//
// Tree shaking of Cast/AirPlay/PiP/Remote Playback happens in build.mjs via esbuild resolver
// overrides, so the feature lists here stay upstream's defaults.
import "@videojs/html/video/player";
import "@videojs/html/live-video/player";
import "@videojs/html/video/skin";
import "@videojs/html/live-video/skin";
import "@videojs/html/media/hlsjs-video";
import "@videojs/html/media/dash-video";

// All 50 locale packs, because the skin already drags their translation tables into the bundle whether
// they are registered or not — this import only adds the registration loop, ~650 bytes. Registering the
// exact app language instead would cost the same and add a Kotlin-to-Video.js locale mapping to maintain.
import "@videojs/html/i18n/locales/all/register";

// No stylesheet import: upstream's baseline sheet is bundled with its elements, while reader sizing
// and controls are supplied by @nekori/reader. A second bundle stylesheet would compete with both.
