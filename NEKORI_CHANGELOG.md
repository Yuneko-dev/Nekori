# Nekori Changelog

All notable changes **this fork** makes on top of Tsundoku are documented in this file.

`CHANGELOG.md` is Tsundoku's own release record and is kept byte-identical to upstream so it can be
merged without conflict — nothing about Nekori belongs in it. Everything below is work that exists
only here.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [Unreleased]

### Added

#### JS plugin runtime
- Headless React Native + Hermes runtime hosting LNReader plugins, behind a Kotlin facade with a typed Kotlin↔JS command bridge.
- Plugins run against standard `fetch`, sharing the app's user agent and cookie jar.
- Plugin modules aligned with LNReader, including web compatibility, plugin assets, storage, filters and settings.
- Novel extension management: install, update, delete, repository handling, plugin details and install state.
- Plugin identity and metadata derived from the installed code by the runtime rather than by parsing source text.

#### Reader
- Paged and volume novel navigation.
- Native find in page, chapter drawer, and a font preview in settings.
- LNReader web interactions and loading skeleton.
- Fullscreen embedded video, later moved onto a bundled Video.js v10 with DASH and Widevine support.
- External subtitles attached by plugins.
- A prompt to resume a video, with the next episode offered on finish.
- Configurable reading margins, volume-key scroll distance, WebView network handling and WebView remote debugging.
- WebView and share actions in the novel bottom bar.
- One `reader.error` API so in-page failures reach the user instead of dying in the console.

#### Text to speech
- TikTok TTS engine.
- MediaSession media notification with transport controls and the novel cover.

#### Translation and AI
- AI provider workflow: multiple providers, per-provider models, custom headers, user guidelines.
- A per-purpose engine choice, so chapter text, entry metadata and browse titles can each use a different engine.
- Chapter chunking by word count or paragraph count, with contextual anchoring for consistency across a chunk seam.
- Parallel chunk translation with a shared requests-per-minute ceiling.
- Background pre-translation of the next chapter.
- Chapter summaries, on a task-neutral LLM client with Settings → AI as the hub.

#### Statistics
- Advanced novel reading insights, reading session tracking and a control to disable it.
- Publication status breakdown, storage usage breakdown and a reading heatmap.

#### Downloads and network
- Video chapter downloads, with embedded image progress and a label for downloaded video chapters.
- HLS streamed straight to MP4 through the hls.js remuxer.
- Local-aware DoH and DPI bypass.
- Domain forwarding rules, applied to resolved plugin URLs and mass import.
- Request throttling scoped to JS plugin traffic, so covers, trackers and translation are not paced by source settings.

#### Elsewhere
- Discord rich presence.
- Novel-only backup and restore overhaul, plus LNReader backup import, including local novels and an
  opt-in for novels whose plugin the backup cannot identify.
- Novel structures and reading sessions in the database.
- Quick filter preset chips in Browse.
- Vietnamese translations for the fork's own strings.

### Changed
- **Rebranded from Tsundoku to Nekori.** `applicationId` is `app.yuneko.nekori`, so this installs alongside Tsundoku rather than upgrading it — moving data across is a backup and restore. Discord's OAuth callback moved to `nekori://discord-auth`. CI, the in-app updater and repository links point at `Yuneko-dev/Nekori`.
- Contextual anchoring is off by default. It only matters once a chapter is split, and it costs the chapter its parallelism because a chunk cannot start before the one ahead of it finishes.
- Duplicate detection rebuilt on Material 3; the duplicate-URL mode was dropped.
- The statistics interface choice moved into settings.
- The reader is native WebView plus Compose; React Native is the plugin runtime only.

### Removed
- The manga page viewer, the native image decoder and the Fresco stack.
- Legacy Kotlin extension discovery, and the Shizuku extension installer.
- Obsolete manga download preferences.
- Firebase configuration, with release telemetry disabled.
- The FOSS build. Upstream needs it because the regular build ships Firebase and F-Droid will not take
  that; this fork dropped Firebase, so the two builds were the same app under two package names, built
  twice on every release.
- The preview build. It and the nightly build came off the same branch with the same `r{commit count}`
  versioning and the same signing, differing only in cadence, and preview's recipe lived in a second
  repository that had to mirror every change to the main one. Nightly is the only unstable channel now,
  which also collapses `isPreviewBuildType` into `isNightlyBuildType`.

### Fixed

#### Reader
- Chapter reload actually repaints the viewer, and a forced reload outlives the plugin chapter-text cache.
- Duplicate anchors scoped to the current chapter.
- Video chapters offset by the measured header height.
- Gesture classification owned by the DOM, and `navigationModeNovel` governing the novel tap zones.
- Inline error auto-dismiss starts when the error becomes visible, not when it is created.
- The image modal hides when closed instead of leaving a broken-image icon.
- A race when loading a chapter, one-shot chapter titles, chapter spacing, EPUB navigation and novel metadata.
- Infinite scroll appends past a run of chapters shorter than the viewport.
- Local novel reading, chapter images, and novel themes aligned with video styling.

#### Plugins and sources
- Raw plugin paths preserved: a path is opaque source identity, not a URL to normalize.
- JS source registration awaited before background work starts.
- Installed plugin code version verified against the repository entry.
- Repository files honoured and `.js` filenames preserved through SAF.
- A plugin rescan forced when a JS repository is toggled.
- Unavailable plugin modules tolerated so a plugin probing for an optional helper still loads.
- JS plugin incognito restored, and the app user agent used consistently in WebView.

#### Translation
- LLM output aligned by paragraph index rather than by position alone.
- Request timeout honoured by every engine.
- Cancelling a chapter no longer kills the queue for the rest of the process.
- AI requests paced before they are issued rather than inside them, so a low limit no longer turns throttling into timeouts.

#### Tracking
- The `mihon://` callback scheme restored, so Bangumi and Shikimori can log in again. Those two still use mihon's OAuth client ids, so their registered redirect is mihon's and cannot move.

#### Elsewhere
- The download cache stops re-indexing the whole downloads tree on every cold start.
- Found chapter directories keyed by chapter id.
- The novel queue sampled instead of debounced.
- Migration background jobs survive a cold start and a resume.
- The last-read sort stays fresh after reading.
- The full app language list restored.
- Deletion targets in duplicate detection materialized in chunks.
- Missing-cover scanning skipped for EPUBs.
- Video progress saved more accurately, and downloaded video MP4 remuxing repaired.

### Other
- A downloaded chapter archive is opened once rather than per read.
- Fresco native libraries dropped from the APK, and the react-native barrel import removed from the JS runtime.
- Chapter text returned directly from the runtime instead of round-tripping.
- JavaScript stack traces preserved across the bridge.
- Automatic video conversion setting.

## Upstream Sync

Nekori is based on Tsundoku v0.3.1 (as of August 2026).

Upstream changes from Tsundoku are tracked in [CHANGELOG.md](./CHANGELOG.md).
