## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default labels apply: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context layout (`GLOSSARY-MAP.md` + `docs/contexts/<name>/GLOSSARY.md`, all ADRs centralized in `docs/adr/`). See `docs/agents/domain.md`.

### Toolchain

`packageManager: "bun@1.4.4"`. JS workspace uses Bun + Turborepo (`packages/*` in ADR-0002, current paths `js-runtime` + `app/tools/*`). Kotlin stays Gradle (`spotlessCheck`, `ktlint`). Run `bun fmt` before `bun lint`. `js-runtime` is self-contained (keeps `js-runtime/node_modules` for RN Gradle). Hermes stays the shipped engine; `test:chapter` and RN Gradle resolve stay on Node until proven on Bun.
