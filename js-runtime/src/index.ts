/**
 * Entry point for the headless React Native runtime.
 *
 * There is no React component tree here and there never will be — the UI is Kotlin/Compose. This
 * bundle exists so plugin code can run on Hermes with npm libraries resolved by Metro.
 */

// MUST be first. React Native's polyfills — `setImmediate`, timers, `console`, error handling — are
// installed by InitializeCore, and `react-native/index.js` does NOT pull it in. A React Native app
// gets it because Metro's `getModulesRunBeforeMainModule` prepends it for the standard entry point;
// a bare headless bundle has to ask. Without it `setImmediate` is undefined, `startBridge()` throws
// during bundle evaluation, and the only symptom is that `ready()` never arrives.
import 'react-native/Libraries/Core/InitializeCore';

import { AppRegistry } from 'react-native/Libraries/ReactNative/AppRegistry';

import { registerHandler, startBridge } from './bridge/nativeHost';
import { getPlugin, initPlugin } from './plugins/pluginHost';

declare const global: { __TSUNDOKU_JS_READY__?: boolean };

// M0 spike handlers. Task 8 replaces these with the real plugin surface.
registerHandler('sum', (args) => {
  const { a, b } = args as { a: number; b: number };
  return { result: a + b };
});

registerHandler('boom', (args) => {
  const { message } = args as { message: string };
  throw new Error(message);
});

// Never settles. Exists so the Kotlin side can be tested for cancellation and pending-map cleanup —
// a call that is abandoned must not leak its continuation.
registerHandler('never', () => new Promise<never>(() => {}));

registerHandler('plugin.load', (args) => {
  const { id, code } = args as { id: string; code: string };
  const plugin = initPlugin(id, code);
  return { id: plugin.id, name: plugin.name, version: plugin.version, site: plugin.site };
});

registerHandler('plugin.popularNovels', async (args) => {
  const { id, page } = args as { id: string; page: number };
  const plugin = getPlugin(id);
  const novels = await plugin.popularNovels(page, {
    showLatestNovels: false,
    // Not `undefined`. Plugins dereference their own declared filters — Báo Mới reads
    // `filters.page.value` — so the host is expected to materialize the plugin's defaults, each of
    // which already carries a `value`. M1's filter system has to do this properly; passing
    // `undefined` is what LNReader's type signature allows and what real plugins crash on.
    filters: plugin.filters,
  });
  return { novels };
});

registerHandler('plugin.searchNovels', async (args) => {
  const { id, query, page } = args as { id: string; query: string; page: number };
  return { novels: await getPlugin(id).searchNovels(query, page) };
});

registerHandler('plugin.parseNovel', async (args) => {
  const { id, path } = args as { id: string; path: string };
  return { novel: await getPlugin(id).parseNovel(path) };
});

registerHandler('plugin.parsePage', async (args) => {
  const { id, path, page } = args as { id: string; path: string; page: string };
  return { page: await getPlugin(id).parsePage(path, page) };
});

registerHandler('plugin.parseChapter', async (args) => {
  const { id, path } = args as { id: string; path: string };
  const html = await getPlugin(id).parseChapter(path);
  // The chapter body can be hundreds of KB; the spike only needs to know it arrived and is HTML.
  return { length: html.length, head: html.slice(0, 120) };
});

AppRegistry.registerHeadlessTask('TsundokuJsRuntime', () => async () => {
  startBridge();
  global.__TSUNDOKU_JS_READY__ = true;
  console.log('[tsundoku] headless JS runtime started');
  await new Promise<never>(() => {});
});

console.log('[tsundoku] js runtime evaluated');

export {};
