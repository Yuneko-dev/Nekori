# Android DevTools (Lite)

The final Reader overflow action (below Chapter summary) opens the genuine Chii
DevTools Frontend. The ordinary browser WebView has no embedded debugger action.
Split mode resizes the native Reader pane, including its chrome, and DevTools into
separate parts of the window. Drag the handle between 10% and 90%, use Layers to
switch to overlay (Reader stays full size), and Swap to put DevTools above/below
the Reader. The same frontend/session survives resize, mode changes, swapping and
rotation. The handle also supports accessible progress adjustment.
The compact toolbar is 28dp high with 16dp icons, matching the frontend's controls.
Repeating the menu action while open is a no-op. The original DevTools toolbar Close
button or Android Back ends the session and restores the full Reader; the menu can
then reopen it. There is no custom title or floating launcher. Only en-US is bundled.
Device emulation, screencast, throttling and Chromium Issues descriptions are excluded.
The retained panels are Elements, Console, Sources (Page/Snippets) and Application.
Network, Elements Layout and their commands/preferences are removed from the source
and tree-shaken from the distribution. Shared resource models and Application's
Cache Storage preview remain available.
Settings retains supported frontend Preferences and Shortcuts. Eruda is not included.
Chrome remote debugging, a remote server and runtime asset downloads are unnecessary.

The inspected document receives Chobitsu on demand. A fresh, random session nonce
binds an Android WebMessagePort to that document. A separate WebView hosts the packaged
frontend directly at `https://nekori.devtools`, without an iframe or postMessage host
wrapper. The upstream EmbeddedConnection uses `__chiiNativeTransport` when present;
only the exact-origin main frame can use the native relay. The normal iframe demo
continues to use the original postMessage connection.
The frontend WebView blocks all requests outside its packaged asset subtree.
Closing DevTools, document navigation or target detachment ends the connection.
The inspected page remains loaded, and the reader's `window.nekori` bridge is untouched.
Chobitsu 1.8.6 has no teardown API: its wrappers, observers and captured data remain
in the document until reload. Closing disconnects the native transport and destroys
the frontend; reopening reuses the backend to avoid double wrapping. Reload the target
page to remove Chobitsu completely.

Console, DOM/CSS, Sources Page/Snippets and supported browser storage use Chobitsu's
real CDP implementation. Engine breakpoints remain unsupported.

## Building and updating the frontend

`dist.lock.json` records the frontend repository and a full **dist commit SHA**.
Its `commit` pins a published distribution; updates require changing this lock explicitly.
Android asset preparation fails before accessing Git when this pin is absent or invalid.
There is no latest-version or checked-in-asset fallback. A `chii` source SHA is not
a dist SHA and is rejected if it is outside dist history.

In the frontend repository, pushes to `chii` trigger the release build and browser
tests, then publish the tested, clean `release/` tree to `dist`. The first dist
commit is an independent root with no source ancestry. Each later commit replaces
the whole artifact tree, removes stale files and retains the previous dist commit
as its parent. Publication uses an explicit `--force-with-lease`; a concurrent
update fails instead of overwriting another release. PR builds do not publish.

After that workflow succeeds, copy the resulting **dist** commit into
`app/tools/devtools-lite/dist.lock.json` and review/commit that lock with the app
changes. No frontend source checkout or depot_tools is needed to build Nekori.
Git and Node must be on PATH. The normal Android build runs `:app:prepareDevToolsAssets`:

1. Clone only `dist`, initially at depth 1, without tags or source branches.
2. Deepen only dist history if needed to locate an older pin; check out that exact
   commit detached. Never fetch an arbitrary SHA from another branch.
3. Verify the clean-build marker, required assets, paths and SHA-256 manifest before
   replacing generated assets. Disable Git newline conversion to preserve their bytes.
4. Put frontend, Chobitsu, manifest and licenses in
   `app/build/generated/assets/devtools/devtools-lite/`. Gradle packages them and
   generates the license list from that same manifest for every variant.

The reusable Git cache is under `.gradle/devtools-dist/`. A cached pin can build
offline. Both the cache and generated assets are ignored by Git. Only the native
`android.html` and `target.js` hosts remain in `app/src/main/assets/devtools-lite/`;
the imported copies have been removed and that directory ignores other assets.
Frontend dependency notices are maintained in its `scripts/chii/THIRD-PARTY-NOTICES.txt`
and published with the distribution. Runtime still loads packaged assets entirely offline.

Run the build-script checks without a published repository:

```text
node app/tools/devtools-lite/test-dist.mjs
```

This uses local Git fixtures to check pin enforcement, old pins, dist-only object
downloads, wrong-branch rejection, offline reuse, stale-file removal and corrupt hashes.
It prints a temporary lock usable with `-PdevToolsLockFile=ABSOLUTE_TEST_LOCK_PATH`
to verify the real Gradle preparation task. That explicit override is for local
verification; it does not alter the checked-in lock. Fixture assets are not a usable UI.

After preparing a real pinned distribution, check its packaged closure with:

```text
node app/tools/devtools-lite/check-assets.mjs
```

Device verification requires a real pinned frontend: `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and
`DevToolsLiteTest` and `DevToolsLiteMenuTest` on a device. The tests deliberately disable
Chrome remote debugging and check the Reader connection, absence from the ordinary
WebView menu, genuine DOM rendering, native pane resizing, ratio limits, overlay,
swap, rotation, repeated menu actions without duplicate sessions, backend reuse,
Close/Back, and collection of frontend WebViews/the destroyed Reader Activity.
