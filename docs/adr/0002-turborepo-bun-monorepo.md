# Monorepo split: apps/android + packages, Bun dev, Gradle stays

The repo splits to `apps/android/` as the Gradle root and `packages/*` for JavaScript only, with Bun as the dev runtime for install, scripts, and tests, while Hermes remains the shipped plugin engine and Gradle remains the Android build. Docs are centralized: `GLOSSARY-MAP.md` links to `docs/contexts/*/GLOSSARY.md` today, and every ADR lives in `docs/adr/`; the future move only updates MAP links. The trade-off is one clear JS workspace with caching, at the cost of a staged path migration that must not break RN Gradle resolution.

## Status

accepted

## Considered Options

- **JS-only nested workspace**: rejected by request. It would keep `app/` and `js-runtime/` in place and never reach the required `apps/` + `packages/` shape.
- **Full Turborepo claiming Kotlin**: rejected as infeasible. Turborepo has no AGP, SQLDelight, Compose, or R8 model; `assembleRelease` stays Gradle. Turborepo orchestrates `packages/*` tasks only.
- **Node for everything**: rejected. Team rule is Bun-first: check `bun.com/docs/runtime/*` built-ins before adding libraries (`libraries.md` #15). `Bun.password` is not needed here (no passwords), so no rotation applies.

## Consequences

- `packages/*` converts the four JS packages (`js-runtime`, `@nekori/reader`, `cheerio-bundle`, `videojs-bundle`) to Bun (`bun install`, `bun run`, `bun test --check` for pure TS only; Hermes-dependent `test:chapter` stays on Node until verified). A `node` shim stays only where the RN Gradle plugin resolves via `node` at settings time; `js-runtime/` stays the npm root until a dedicated path-move update.
- Gradle build-logic and all 17 modules stay under `apps/android/`; Kotlin is never published as an npm package. If sharing returns, use Kotlin Multiplatform for extracted pure logic, not JS conversion.

## Rollout

1. **Now (no moves)**: `GLOSSARY-MAP.md` + 5 context glossaries under `docs/contexts/` + these ADRs in `docs/adr/`. No `turbo.json` or file moves.
2. **Workspace**: add a root Bun workspaces setup covering `packages/*` only; unify the two managers (pnpm + npm) and one lockfile. Update CI install lines together.
3. **Turbo**: add `turbo.json` for `build`, `typecheck`, `lint`, `test` with `videojs-bundle → reader-runtime` ordering (committed vendor assets feed the reader dist).
4. **Move**: relocate TS sources under `packages/` (keeping `js-runtime/` as the RN root first); update `settings.gradle.kts` node-resolve, Metro `projectRoot`, and `BuildReaderRuntimeAssets` paths in one change. Codegen stays single-sited in `:app` only, or release R8 duplicates.
