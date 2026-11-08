# Sources & Plugins

Where Entries and Chapters come from.

## Language

**Source**:
A content origin that supplies Entries and Chapters, either a JS plugin or a local source.
_Avoid_: Extension, Site

**Plugin**:
An LNReader-compatible JavaScript module that implements a Source.
_Avoid_: Extension, APK, Kotlin extension

**Plugin store**:
A signed repository index listing available Plugins.
_Avoid_: Extension store, App store

**Local source**:
An on-device Source reading user files such as EPUB, TXT, and HTML.
_Avoid_: Import, File source

**Novel source**:
A Source with novel support enabled.
_Avoid_: NovelSource (deprecated name)

**Chapter content**:
The normalized chapter payload supplied by a Source, with state, type, HTML, and cache flags.
_Avoid_: Raw HTML, Page

**Metadata interchange**:
The local-file metadata standard carried in a file or archive.
_Avoid_: MangaDetails (legacy JSON)

**Stub source**:
A placeholder Source with no live data.
_Avoid_: Installed source
