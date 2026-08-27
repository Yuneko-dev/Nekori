<div align="center">

<img src="./.github/assets/logo.png" alt="Nekori logo" title="Nekori logo" width="80"/>

# Nekori [App](#)

### Novel reader for LNReader plugins
A personal fork of [Tsundoku](https://github.com/tsundoku-otaku/tsundoku), built around the LNReader plugin ecosystem.

[![GitHub downloads](https://img.shields.io/github/downloads/Yuneko-dev/Nekori/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/Yuneko-dev/Nekori/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/Yuneko-dev/Nekori/build.yml?labelColor=27303D)](https://github.com/Yuneko-dev/Nekori/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/Yuneko-dev/Nekori?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

> [!IMPORTANT]
> **Nekori does not support Kotlin extensions.** Nothing built against `tachiyomix` will load — not
> Tachiyomi's, not Mihon's, not Tsundoku's. The only content sources are **LNReader plugins**.
>
> So yes: this is a fork of a fork of Tachiyomi that cannot run a single Tachiyomi extension. Funny, isn't it.

> [!WARNING]
> This is a personal fork, made for my own use. It is not a community project and is not recommended for production
> use. Expect breaking changes without notice.

> [!NOTE]  
> This fork uses AI slop.

## Download

[![Nekori Stable](https://img.shields.io/github/release/Yuneko-dev/Nekori.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/Yuneko-dev/Nekori/releases/latest)

*Requires Android 8.0 or higher.*

Nekori installs alongside Tsundoku rather than upgrading it — the application id is `app.yuneko.nekori`. Moving your
library across is a backup and a restore.

## Features

Most of this comes from Tsundoku and, before it, Mihon. Nekori keeps it rather than rebuilds it — the list below is
what the app does, not what this fork added. For the fork's own changes, see
[NEKORI_CHANGELOG.md](./NEKORI_CHANGELOG.md).

<div align="left">

**Sources**

* LNReader plugins, run by a headless React Native + Hermes runtime, against standard `fetch`.
* Plugins share the app's user agent and cookie jar, so logins and Cloudflare clearances carry over.
* Install, update and remove plugins from repositories, with plugin details and install state.
* Local reading of content, including EPUB, TXT and HTML.

**Reader**

* Paged and volume novel navigation, a chapter drawer, native find in page, and a font preview in settings.
* Configurable reading margins, volume-key scroll distance and WebView network handling.

**Translation and AI**

* Multiple AI providers, per-provider models, custom headers and user guidelines.
* A separate engine per purpose — chapter text, entry metadata and browse titles can each use a different one.
* Chapter chunking by word or paragraph count, with optional contextual anchoring across a chunk seam.
* Parallel chunk translation under a shared requests-per-minute ceiling.
* Background pre-translation of the next chapter, and chapter summaries.

**Network**

* DNS over HTTPS and DPI bypass, applied locally.
* Domain forwarding rules, and request throttling scoped to plugin traffic so covers, trackers and translation are
  not paced by source settings.

**Library**

* Tracker support: [NovelUpdates](https://www.novelupdates.com/), [NovelList](https://www.novellist.co/) and
  [RanobeDB](https://ranobedb.org/), plus the classics — [MangaBaka](https://mangabaka.org),
  [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/),
  [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/) and
  [Hikka](https://hikka.io/).
* Categories, light and dark themes, and scheduled library updates.
* Read-ahead chapter downloads for offline reading.
* Novel-only backup and restore, plus LNReader backup import.
* Reading statistics: session tracking, publication status and storage breakdowns, and a reading heatmap.
* Discord Rich Presence.

</div>

## Differences from Tsundoku

Tsundoku is a Mihon fork that added novel support while keeping the manga side intact. Nekori drops that side entirely:

* The manga page viewer, the native image decoder and the Fresco stack are gone.
* Kotlin extension discovery and the Shizuku extension installer are gone.
* Firebase configuration is gone, and release telemetry is disabled.
* React Native is the plugin runtime only — the reader itself is a native WebView plus Compose.

Everything else stays. The library, categories, trackers, themes, backups, downloads, statistics and the whole
settings surface are Tsundoku's, and Nekori follows upstream rather than diverging from it — this fork is a narrowing,
not a rewrite.

The full list lives in [NEKORI_CHANGELOG.md](./NEKORI_CHANGELOG.md). [CHANGELOG.md](./CHANGELOG.md) is Tsundoku's own
release record and is kept identical to upstream so it merges cleanly.

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

This is a personal project rather than a community fork, so it does not have a dedicated Discord server or
translation project yet. Questions and bug reports are welcome in
[Discussions](https://github.com/Yuneko-dev/Nekori/discussions) or
[Issues](https://github.com/Yuneko-dev/Nekori/issues); I will try to respond as soon as I can.

### Credits

Nekori exists because of work done elsewhere:

* [Mihon](https://github.com/mihonapp/mihon) and [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) — the app this
  is descended from.
* [Tsundoku](https://github.com/tsundoku-otaku/tsundoku) — the novel support this fork is built on.
* [LNReader](https://github.com/LNReader/lnreader) — the plugin ecosystem that makes this app worth using, and the
  backup format it imports.

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this
application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2026 Nekori

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
