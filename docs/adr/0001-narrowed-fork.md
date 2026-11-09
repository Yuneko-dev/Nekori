# LNReader plugins only — narrowed fork

Nekori is a Tsundoku fork narrowed to novels: it supports only LNReader and Nekori JavaScript plugins and no Kotlin extensions (`tachiyomix`), and the manga viewer, Fresco stack, Shizuku installer, and Firebase configuration are absent from release artifacts (sources retained behind gates where noted). The trade-off is a smaller, mergeable fork that follows upstream for library, categories, trackers, themes, backups, downloads, and statistics, at the cost of no Tachiyomi/Mihon extension compatibility.

## Status

accepted

## Considered Options

- **Keep Kotlin extensions**: rejected. It would retain APK discovery, Shizuku, and `tachiyomix` ABI paths that diverge from LNReader and grow maintenance cost.
- **Rewrite from scratch**: rejected. The library, settings, trackers, and backup surface are inherited and working.

## Consequences

- `ExtensionManager` stays inert for legacy callers; code names such as `Manga`, `SManga`, `ExtensionStore` are frozen for database and backup compatibility. `Entry` is product prose; `Manga` with `isNovel` remains the code row.
- Telemetry source remains behind the `-Pinclude-telemetry` gate so upstream merges stay clean; release builds ship the noop variant.
