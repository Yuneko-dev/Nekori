Looking to report an issue/bug or make a feature request? Please refer to the [README file](https://github.com/Yuneko-dev/Nekori#issues-feature-requests-and-contributing).

---

Thanks for your interest in contributing to Tsundoku!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/Yuneko-dev/Nekori/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Automated checks

- `.github/workflows/kotlin-checks.yml` is reused by the PR/main build and tagged release
  workflows. Its independent jobs run `./gradlew spotlessCheck --continue` and
  `./gradlew testDebugUnitTest verifySqlDelightMigration --continue` before APK builds.
  Failed tests upload reports from all modules. The workflow can also be run manually.
- `.github/workflows/js-runtime-checks.yml` runs JS runtime type checking, ESLint, Prettier
  and chapter-content tests independently. Locally, run `pnpm run check` and
  `pnpm run test:chapter` from `js-runtime/`.
- `.github/workflows/reader-checks.yml` runs `npm run check` from `app/tools/reader-runtime/`:
  TypeScript checking, ESLint (including whitespace style checks), build and Node tests.
  Chromium acceptance tests are excluded from `npm test` and Ubuntu CI; `npm run browser`
  remains an explicit local command. See the [reader README](app/tools/reader-runtime/README.md).

The JS and reader workflows run on relevant PR changes, pushes to `main`, `v*` tags and
manual dispatch. Checks report failures without automatically rewriting source files.

## Getting help

- Join [the Discord server](https://discord.gg/RycBkEgrPE) for online help and to ask questions while developing.

# Translations

Translations are done externally via Weblate. See [our website](https://nekori.yuneko.dev/docs/contribute#translation) for more details.


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/Yuneko-dev/Nekori/blob/main/LICENSE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](https://github.com/Yuneko-dev/Nekori/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
- To avoid installation conflicts:
    - Change the `applicationId` in [`build.gradle.kts`](https://github.com/Yuneko-dev/Nekori/blob/main/app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use Firebase analytics, replace [`google-services.json`](https://github.com/Yuneko-dev/Nekori/blob/main/app/src/standard/google-services.json) with your own
