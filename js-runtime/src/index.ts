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

import { registerHandler, startBridge } from './bridge/nativeHost';

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

startBridge();

global.__TSUNDOKU_JS_READY__ = true;
console.log('[tsundoku] js runtime evaluated');

export {};
