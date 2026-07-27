const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const path = require('path');

const packageDirectory = (name) => path.dirname(require.resolve(`${name}/package.json`));

/**
 * The project root is this directory, not the Gradle root — package.json and node_modules live
 * here. See docs/superpowers/plans/2026-07-27-m0-rn-brownfield-spike.md.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
module.exports = mergeConfig(getDefaultConfig(__dirname), {
  projectRoot: __dirname,
  resolver: {
    // Metro deliberately does not provide Node core modules. Plugins and crypto-browserify expect
    // the Node names, so point those names at the explicit browser packages we ship.
    extraNodeModules: {
      buffer: packageDirectory('buffer'),
      crypto: packageDirectory('crypto-browserify'),
      stream: packageDirectory('stream-browserify'),
    },
  },
});
