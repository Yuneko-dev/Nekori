// Registers exactly the custom elements the Video.js CDN preset registers, and nothing else.
//
// Everything here is a side-effect import: each module calls safeDefine() with upstream's own tag
// name. Do not export a Tsundoku-specific façade or define Tsundoku-specific tag names — core-player.js
// must be able to run against a CDN build of the same version with no code changes, and it can only do
// that if the element names it asks for are upstream's. Player composition (skin, poster, thumbnails,
// stripping unsupported controls) is plain DOM and lives in core-player.js for the same reason.
//
// Tree shaking of Cast/AirPlay/PiP/Remote Playback happens in build.mjs via esbuild resolver
// overrides, so the feature lists here stay upstream's defaults.
import "@videojs/html/video/player";
import "@videojs/html/live-video/player";
import "@videojs/html/video/skin";
import "@videojs/html/live-video/skin";
import "@videojs/html/media/hlsjs-video";
import "@videojs/html/media/dash-video";

// The default video skin's template never uses this one, so importing the skin alone leaves it
// undefined. core-player.js adds it to the controls for the anime intro/outro skip.
import "@videojs/html/ui/seek-button";

// All 50 locale packs, because the skin already drags their translation tables into the bundle whether
// they are registered or not — this import only adds the registration loop, ~650 bytes. Registering the
// exact app language instead would cost the same and add a Kotlin-to-Video.js locale mapping to maintain.
import "@videojs/html/i18n/locales/all/register";

// No stylesheet import: upstream's own baseline sheet already sets `display: contents` on the player
// elements, and reader-side sizing belongs to core-player.css. A sheet here would emit a second asset
// that fights both.
