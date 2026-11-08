/**
 * The React Native CLI expects the standard layout where the Gradle root is an `android/` folder
 * next to package.json. Here it is the other way round: the Gradle root is the repo root and the
 * npm root is this directory, so the CLI cannot infer the Android project and
 * `generateAutolinkingPackageList` fails with "Could not find project.android.packageName".
 *
 * `sourceDir` MUST be relative to this directory. The CLI does
 * `path.join(root, userConfig.sourceDir)` (cli-config-android `projectConfig`), and `path.join` —
 * unlike `path.resolve` — does not treat an absolute second argument as a new root. Passing an
 * absolute path yields `…/js-runtime/C:/…/tsundoku-ext`, which matches nothing, so `project.android`
 * silently resolves to `null` instead of erroring.
 *
 * @type {import('@react-native-community/cli-types').Config}
 */
module.exports = {
  project: {
    android: {
      sourceDir: '..',
      appName: 'app',
      // Java package for the generated PackageList class — matches :app's `namespace`.
      packageName: 'eu.kanade.tachiyomi',
    },
  },
};
