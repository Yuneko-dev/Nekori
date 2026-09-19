# Video.js reader bundle

Pinned Video.js 10 RC2 and HLS.js 1.7.3. Run `npm ci`, then `npm run build`
from this directory. `npm run check` validates patches, locale registration,
required controls and exclusion of Cast/AirPlay/PiP without writing assets.

`npm test` requires Chrome, FFmpeg/ffprobe on PATH and an existing Playwright
installation. If Playwright is outside this directory, set `PLAYWRIGHT_MODULE`
to its absolute `index.mjs` path. Tests generate temporary media and verify
HLS/DASH/direct playback, live skin, controls, recovery, and decoded downloads
(plain HLS, AES-128 and MP3 fallback, with and without workers).

The HLS source patch is in `../hls-bundle/hls-1.7.3-tsundoku.patch`; `build.mjs`
applies the equivalent changes to the npm distribution. Worker event forwarding
is already fixed upstream. Keep download capture disabled during playback.
