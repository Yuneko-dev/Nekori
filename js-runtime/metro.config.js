const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * The project root is this directory, not the Gradle root — package.json and node_modules live
 * here. See docs/superpowers/plans/2026-07-27-m0-rn-brownfield-spike.md.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
module.exports = mergeConfig(getDefaultConfig(__dirname), {
  projectRoot: __dirname,
});
