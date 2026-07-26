/**
 * Entry point for the headless React Native runtime.
 *
 * There is no React component tree here and there never will be — the UI is Kotlin/Compose. This
 * bundle exists so plugin code can run on Hermes with npm libraries resolved by Metro.
 */

declare const global: { __TSUNDOKU_JS_READY__?: boolean };

global.__TSUNDOKU_JS_READY__ = true;

// Read from Kotlin in the M0 spike to prove the bundle actually evaluated.
console.log('[tsundoku] js runtime booted');

export {};
