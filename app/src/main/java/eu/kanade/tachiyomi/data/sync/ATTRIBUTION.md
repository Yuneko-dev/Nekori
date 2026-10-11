# Sync feature attribution

Cross-device library sync (WebDAV and Google Drive) in Tsundoku is substantially
derived from [Komikku](https://github.com/komikku-app/komikku)
(`eu.kanade.tachiyomi.data.sync` and related settings / preferences),
Copyright © Komikku contributors.

Komikku is licensed under the Apache License, Version 2.0.
A copy of that license is available in the repository root `LICENSE` file
(same license as Tsundoku).

## Tsundoku adaptations

- SyncYomi service omitted; only WebDAV and Google Drive are supported.
- Manga and novels are included in sync by default.
- Google Drive OAuth client ID/secret are supplied at build time via
  gitignored `local.properties` (or environment variables), not a committed
  `client_secrets.json` asset.
- OAuth redirect uses Google’s Android custom-scheme form
  `com.googleusercontent.apps.<clientId>:/oauth2redirect` so it does not
  collide with Komikku’s `eu.kanade.google.oauth` handler when both apps are
  installed.

## Related packages

Derived or adapted settings/preferences also appear under:

- `eu.kanade.domain.sync`
- `eu.kanade.presentation.more.settings.screen.data` (sync settings UI)
- `eu.kanade.tachiyomi.ui.setting.track.GoogleDriveLoginActivity`
