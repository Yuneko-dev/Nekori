module.exports = {
  presets: ['@react-native/babel-preset'],
  plugins: [
    // cheerio pulls htmlparser2, whose ESM build uses `export * as ns from '...'`. React Native's
    // preset does not enable that transform, so Metro fails with "Export namespace should be first
    // transformed by @babel/plugin-transform-export-namespace-from".
    '@babel/plugin-transform-export-namespace-from',
  ],
};
