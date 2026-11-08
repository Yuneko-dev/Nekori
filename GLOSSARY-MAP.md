# Glossary Map

Multi-context vocabulary for Nekori. Each context owns a `GLOSSARY.md` under `docs/contexts/<name>/`. `docs/adr/` holds every ADR (centralized by decision — no per-context ADR folders).

## Contexts

- [Library](./docs/contexts/library/GLOSSARY.md): entries, chapters, categories, history, tracks
- [Sources & Plugins](./docs/contexts/sources-plugins/GLOSSARY.md): sources, plugins, plugin stores, local sources, chapter content
- [Reading](./docs/contexts/reading/GLOSSARY.md): reader, viewer, pages, snippets, offline markers, reader API, text-to-speech
- [Translation & AI](./docs/contexts/translation-ai/GLOSSARY.md): purposes, engines, providers, guidelines, translated chapters, summaries
- [Platform & Sharing](./docs/contexts/platform/GLOSSARY.md): tracker services, backups, sync, downloads, statistics, updates, browse, migration, home-screen widgets, themes

## Relationships

- **Sources & Plugins → Library**: a Source supplies Entries and Chapters that populate the Library.
- **Sources & Plugins → Reading**: a Source supplies Chapter content that the Reader renders.
- **Library → Reading**: a Chapter references its Entry; the Reader loads that Chapter.
- **Reading ↔ Translation & AI**: the Reader triggers translation or summaries; Translation stores Translated chapters the Reader displays.
- **Platform & Sharing ↔ Library**: Backup snapshots the Library; Sync pushes Library progress; Download pre-fetches Chapters for offline use.
