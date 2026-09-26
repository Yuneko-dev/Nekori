# Nekori Changelog

User-facing changes in Nekori releases, including adopted upstream improvements, are documented here.

`CHANGELOG.md` is Tsundoku's own release record and is kept byte-identical to upstream so it can be
merged without conflict — Nekori release notes belong here.

Each release describes the final changes since the previous release, not individual development
commits. List new and removed features once; fold fixes made while developing a new feature into its
feature entry. Reserve `Fixed` for bugs affecting previously released functionality.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [Unreleased]

### Improved
- Expand the reader chapter drawer to include all chapters already in the filtered reader list, with labeled dividers between pages or volumes. Preserve chapter order and scroll targets without fetching unloaded pages, and avoid rescanning the chapter list for the current position while scrolling.
- Clean decorative symbols and separator-only lines from TTS input without changing displayed text; preserve numeric punctuation and keep paragraph selection/highlighting aligned.
- Group advanced reader switches above a divider, place find-and-replace immediately below the divider and before CSS/JS snippets with the active rule count, and keep a disabled search icon in empty rule tabs.

### Changed
- Use a shared, theme-independent Catppuccin Frappé palette for publication status and storage statistics; exclude available space from the storage bar and legend.
- Show volume names without an added prefix in the Novel Details section picker; keep page labels for paged novels.

### Fixed
- Fix invisible Android system bars and overlapping reader content when fullscreen is disabled.
- Fix out-of-memory crashes when checking and restoring large LNReader v1/v2 backups: count library metadata with streaming JSON and restore one novel at a time instead of retaining every chapter in memory [#8](https://github.com/Yuneko-dev/Nekori/issues/8)
- Read LNReader ZIPs through the document provider's file descriptor so unused cover/download payloads can be skipped without decompression on seekable files, with a stream fallback for providers without descriptors.
- Fix misleading LNReader import progress: show indeterminate progress while scanning metadata and use the actual novel-file total during restore. Label downloaded chapter counts explicitly in the completion message.
- Preserve LNReader import cancellation, reject malformed preflight JSON and category metadata, clean temporary archives on validation failure, and wait for source initialization before restoring.
- Fix archive stream reads with nonzero buffer offsets and zero-length reads.
- Prevent a crash when automatic next-chapter translation fails, including while reading offline [#7](https://github.com/Yuneko-dev/Nekori/issues/7)

## [v0.1.0] - 2026-09-20

> Finally, the app’s first minor release is here! It may be a little heavier now, but performance should be slightly better… probably.

### Added
- Added global and per-novel find-and-replace rule management, with search, reordering and bulk actions.

### Changed
- Update video playback to Video.js 10 RC2 and HLS.js 1.7.3.

### Improved
- Run plugin crypto and Buffer operations through native Quick Crypto and Nitro Buffer.

### Removed
- 32-bit x86 APKs; x86_64 remains supported.

### Fixed
- Find-and-replace tests now match reader behavior for case sensitivity and escaped replacement characters.
- Retry from the reader error screen clears the chapter cache and fetches fresh source content.

## [v0.0.10] - 2026-09-15

> Just another patch release, still no minor update ;-;

### Added
- Add support for [Nekori-plugins API v1](https://github.com/Yuneko-dev/Nekori-plugins)

### Fixed
- Restore the saved reading position reliably when reopening chapters in paged mode.
- Reader font settings show a loading label instead of briefly displaying the saved font URI.
- TTS media controls appear when playback starts, including from the reader overlay, and remain available while automatically loading the next chapter in the background [#4](https://github.com/Yuneko-dev/Nekori/issues/4)

## [v0.0.9] - 2026-09-09

### Added
- Paged novel reading with automatic, single-page and double-page layouts, reading direction and page-turn effects.
- Customizable reader toolbar actions for chapter search, reload and summary.
- Optional reading-mode and tap-zone hints when opening the reader.
- TTS notification controls support paragraph-based progress and seeking.
- Chapter load errors offer Retry and Copy actions.

### Changed
- Global novel-reader settings use standard preference rows and expose rotation, chapter skipping, tap zones, scrollbars and E-Ink controls; TTS highlight colors include preset swatches and a custom picker.
- Settings preview uses the actual reader, without saving sample history or progress.
- Font selection offers sample previews, consistent built-in choices, unavailable-font indicators and direct deletion; Google Fonts search covers the full catalog.
- AI connection testing verifies a text response from the selected model; provider settings clarify locked endpoints and simplify model selection.

### Removed
- The AI provider temperature setting and temperature parameter in generation requests.

### Fixed
- Vertical reading progress saves the settled position, including 0% after returning to the chapter start; loading and error banners no longer distort chapter progress.
- Failed automatic chapter loads keep the current content on screen.
- Refreshing novel metadata preserves custom fields and memo data, and bulk refreshes update the Library consistently.
- EPUB chapter titles omit redundant book headings while preserving volume and section distinctions.
- Update checks for sources with paginated chapter lists discover new chapters without reloading every page.
- TTS highlighting no longer shifts paragraph layout, and Android TTS releases the reader activity correctly.
- Translation progress no longer appears nearly complete too early during slow parallel requests.

## [v0.0.8] - 2026-08-31

### Added
- Text-to-speech settings now provide shared engine and voice pickers in both global novel-reader settings and the in-reader TTS panel, plus the existing background-playback control.
- Download menus now include an All option that queues every chapter regardless of read state or active chapter filters.

### Changed
- TikTok TTS is now an explicitly labeled online, unstable engine choice instead of a separate experimental toggle.

### Fixed
- Android 11 and newer can discover third-party TTS services such as MultiTTS, and a failed Android TTS initialization can be retried without reopening the reader.

## [v0.0.7] - 2026-08-28

### Added
- Novel reader tap zones now include a medium center zone and an adjustable full-width zone at the top or bottom of the screen.
- Browse sources can now be filtered by plugin name or language using the same search toolbar as extensions.

### Changed
- Plugin details now keep uninstall and website actions side by side; Website opens the plugin site in the in-app WebView instead of Android app information.
- Paragraph auto-split now runs once in the shared content pipeline, using the reader's HTML/plain-text classification before translation instead of repeating a heuristic in each page loader.

### Improved
- Browse, search, filters, plugin settings and runtime metadata now use typed bridge calls instead of compiling a new JavaScript expression for every request.

### Fixed
- Extension version rows no longer inherit a stray leading separator from previously composed metadata.
- Auto-split now preserves TXT whitespace and paragraph breaks, handles inline-only HTML correctly, leaves embedded script/style bodies untouched, and keeps paragraph nodes when TXT chapters are prepended or appended by infinite scroll.
- NovelUpdates tracker requests now use the shared application User-Agent instead of a hardcoded Firefox User-Agent, keeping tracker requests aligned with the app's WebView fingerprint for Cloudflare handling.
- NovelUpdates notes responses with a trailing zero suffix no longer trigger a regex error, restoring chapter-progress reads and updates during tracking.

### Removed
- Redundant `JS` badges and `(JS)` suffixes from user-facing source and extension labels; internal source identity remains unchanged.

### Other
- Recorded Tsundoku through commit `18a0f3c44`, adopting its tap-zone and auto-split changes while retaining Nekori's WebView-only reader and forced JS chapter-cache invalidation.
- Recorded Tsundoku through commit `ad3077439` without importing its legacy QuickJS shim or Kotlin-extension APK deeplink resolver; JS import-domain support remains deferred until plugin metadata declares domains explicitly.

## [v0.0.6] - 2026-08-27

### Changed
- Most read now keeps library novels as individual entries and combines removed novels into one non-interactive placeholder with their total reading time.
- Discord Rich Presence now uses the shared Settings layout and controls while retaining its profile banner, avatar card and logged-out empty state.
- Discord Rich Presence now lets users appear online, idle or in DnD mode, with idle remaining the default, and uses the default EPUB cover when no public online cover is available instead of uploading local covers to Litterbox.

### Fixed
- JS plugin repositories are now checked when the app opens, and newer installed plugins trigger an extension update notification without treating repository downgrades as updates.

### Other
- Synced Tsundoku through commit `2a4ce852f`, adopting upstream memo preservation for download/batch fetch while retaining Nekori's unified AI provider, UserGuidelines and LlmGenerator architecture instead of restoring obsolete per-engine prompt settings.

## [v0.0.5] - 2026-08-26

### Added
- Novel details and all-local Library selections now expose an explicit local-novel file-deletion option that also removes each successfully deleted entry from the Library.
- Daily reading-session details now show novel covers; covers open novel details and session rows reopen the recorded chapter.
- Library filters can now hide and disable the Tags and Extensions filter tabs independently, while preserving their selections for the next time each filter is enabled.

### Changed
- The library Tags filter now uses balanced Material 3 segmented controls, chips and actions.
- Progress bars in the Most read list now compare each novel's reading time with the top-ranked novel instead of showing chapter completion.
- The Most read list can now expand to every title with recorded reading time instead of stopping at the top 20.
- Storage statistics now appear below tracking statistics.

### Fixed
- Deleting a single-file local novel now removes its actual file instead of failing because deletion only looked for a directory.
- Infinite scrolling now keeps the title, current chapter and chapter summaries aligned with the visible content in both directions, even after native page state has been recycled.
- Expand/collapse arrows in Updates groups now line up with chapter download buttons.

## [v0.0.4] - 2026-08-25

### Added
- Optional progress banners show library updates and backup restores in the app, including LNReader imports.

### Changed
- Updates stay organized by date. Multiple chapters from the same novel and day collapse into one expandable row with unread feedback and group selection; single-chapter updates remain regular rows.
- Reading heatmap colors now scale against the busiest day in the selected year, making activity differences easier to see.
- The headless plugin runtime now uses React Native 0.87 and its bundled Hermes compiler.

### Removed
- The separate cross-date "Group by novel" view and its Updates toolbar toggle; the date view now handles repeated novel updates directly.

### Fixed
- Expand/collapse arrows in Updates groups now line up with chapter download buttons.
- Sliders and wheel pickers now provide a haptic tick at every available mark.
- Long-press actions now vibrate once instead of twice.
- Plugins without a site value now fall back to `about:blank` instead of an invalid empty address.


## [v0.0.1] & [v0.0.2] & [v0.0.3] - 2026-08-23

### Added

#### JS plugin runtime
- Headless React Native + Hermes runtime hosting LNReader plugins, behind a Kotlin facade with a typed Kotlin↔JS command bridge.
- Plugins run against standard `fetch`, sharing the app's user agent and cookie jar.
- Plugin modules aligned with LNReader, including web compatibility, plugin assets, storage, filters and settings.
- Novel extension management: install, update, delete, repository handling, plugin details and install state.
- Plugin identity and metadata derived from the installed code by the runtime rather than by parsing source text.
- A Settings → Advanced action to restart the app process and recover a stuck JS engine.
- The native Open Source Licenses screen now includes JavaScript packages actually shipped by the Hermes and WebView bundles.

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
- EPUB export now streams chapters, reports throttled novel/chapter progress with cancellation, keeps canonical source ordering, safely bundles multiple EPUBs, and honors the independent EPUB/ZIP compression settings.
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
- JS plugin repositories now validate absolute HTTP(S) URLs and LNReader manifests before persistence, keep
  actionable failures in the add dialog, confirm deep-link additions, and leave backup/LNReader restore
  network-optional.

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
- Dead novel-irrelevant legacy UI, preference accessors, and resources pruned without changing novel behavior or database/backup compatibility.

## Upstream Sync

Nekori tracks Tsundoku through commit `3cbd993ba634a00813e0c192bea3aab52bc11d83` as of 2026-09-08.
The reader fixes are adapted to Nekori's existing pagination and navigation.

Upstream changes from Tsundoku are tracked in [CHANGELOG.md](./CHANGELOG.md).
