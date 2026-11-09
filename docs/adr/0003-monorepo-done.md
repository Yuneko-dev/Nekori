# Monorepo complete: apps/android + packages, Bun + Turbo - done

The repo now uses `apps/android/` as the Gradle root and `packages/*` for JavaScript. Bun is the dev runtime for install, scripts, and checks; Hermes stays the shipped plugin engine; Gradle remains the Android build. `packages/js-runtime` is self-contained so RN Gradle literal paths stay valid. Turbo orchestrates `packages/*` tasks only.

## Status

done

## Consequences

- All four JS packages (`packages/js-runtime`, `packages/reader`, `packages/cheerio-bundle`, `packages/videojs-bundle`) use Bun. Pure-type checks run with `bun check --no-script`. Hermes `test:chapter` stays on Node. `node` shim stays for RN Gradle resolve.
- Gradle build-logic and all 17 modules live under `apps/android/`. Kotlin is never an npm package.
- `bun fmt` is the baseline formatter. `bun lint` is the baseline linter. ESLint/Prettier remain for existing files until swap proves green.
