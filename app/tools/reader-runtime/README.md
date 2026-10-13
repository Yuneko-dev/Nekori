# @nekori/reader

Reusable TypeScript browser runtime and Chromium WebView bundles for Nekori's novel reader.
The package exports ESM, declarations, and reader CSS. Android classic-script assets are
generated into Gradle's build directory; generated files are not committed to this repository.

```sh
npm ci
npm run check
npm run browser
npm pack
```

To refresh the independent Electron dev client, build here and copy the output explicitly:

```sh
npm run build
node sync-dev-client.mjs /path/to/nekori-plugins
```

The script copies the self-contained ESM runtime, declarations, CSS and offline media vendors to
`nekori-plugins/public/static/novel-reader/`. The plugin repository consumes that local snapshot;
its installation and normal builds do not need this checkout or a reader package dependency.

Package and browser checks run in `.github/workflows/reader-checks.yml`, separately from
APK build/release workflows. Gradle still builds the runtime assets required by the APK.

The browser bundle targets Chromium 138, matching Nekori's minimum supported Android System
WebView. The separate headless download bundle is built from `src/entries/download.ts`; the
upstream-compatible Video.js bundle remains independently built from `app/tools/videojs-bundle`.

## Embedding the runtime

The package exposes the reader factory and its host adapter. The embedding application supplies
the versioned Web Message Listener transport and the parsed document configuration; the runtime
does not discover native bridges from ambient globals. The transport forwards the native message
event's `data` value to `onmessage` and posts serialized protocol envelopes back to native:

```ts
import {
  createReader,
  createRuntimeHost,
  type NativeReaderTransport,
  type ReaderConfig,
} from '@nekori/reader';

declare const config: ReaderConfig; // Read and validate nekori-reader-config JSON.
declare const webMessageListener: {
  postMessage(message: string): void;
  addEventListener(type: 'message', listener: (event: { data: unknown }) => void): void;
};

const transport: NativeReaderTransport = {
  postMessage: (message) => webMessageListener.postMessage(message),
  onmessage: null,
};
webMessageListener.addEventListener('message', (event) => transport.onmessage?.(event));

const root = document.documentElement; // The reader root can be created later in the body.
const host = createRuntimeHost({ root, config, transport });
const reader = createReader({ root, host, config });
await reader.ready; // Controllers are initialized and native has received `ready`.
```

The native side must implement the same `protocolVersion: 1` envelopes and bind a fresh
`documentId` to the WebView document before accepting messages. Construct the host before page
source scripts run so its captured fetch and early message handling are in place. `createReader`
starts the host lifecycle; callers should dispose the returned reader when the document is torn
down. `host.fetch` uses the configured HTTP proxy while `host.fetchNative` bypasses that proxy for
browser-local resources and media sinks.

Android requires WebView **138.0.7204.181 or newer**. Reader, headless download and loading skeleton
assets are built independently; widget construction uses VanJS. Kotlin remains responsible for
chapter loading, speech synthesis, persistence, AI and protected-media policy.

Source scripts use `window.nekori`, whose declaration is included when importing this package:

```ts
import '@nekori/reader';

const reader = window.nekori;
if (!reader) throw new Error('The reader document has not started');
await reader.ready;
const player = reader.player;
if (!player) throw new Error('This document has no media player');
await player.playHls('https://example.com/video.m3u8');
```

The DOM hooks are `.nekori-reader`, `.nekori-chapter` and `data-nekori-chapter-id`. Existing
`--reader-*` font, color and margin variables remain available. User CSS/JS code and snippet
metadata are stored unchanged. Source custom assets run against the new API; LNReader source-data
operations remain handled by the separate native plugin runtime. The old frontend globals and event
aliases are not part of this package. Legacy offline chapter markers are decoded at the native load
boundary into in-memory chapter policy; the downloaded HTML is not rewritten. The package is private
and its `npm pack` command creates a local archive for inspection.

When the host owns speech preprocessing, provide `ttsNormalizationRules` as pattern/replacement
data. The Android adapter supplies its existing native rules so extraction, highlighting and seeks
use the same paragraph indices. Without host rules, the reusable runtime trims leaf-block text.
