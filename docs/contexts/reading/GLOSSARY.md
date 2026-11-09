# Reading

How a Chapter is displayed and controlled.

## Language

**Reader**:
The lifecycle and controls for reading one Chapter.
_Avoid_: Viewer

**Viewer**:
The render surface that displays a Chapter. There is exactly one in Nekori.
_Avoid_: Reader

**Page**:
A rendered text fragment in paged mode.
_Avoid_: Chapter, image page

**Snippet**:
Executable CSS or JavaScript supplied by a source or the user for Chapter display.
_Avoid_: Script, Style (alone)

**Offline marker**:
A legacy marker that forces no-cache or no-prefetch for a Chapter.
_Avoid_: Cache flag

**Reader API**:
The in-document `window.nekori` API for layout, events, and fetch.
_Avoid_: Frontend globals

**TTS engine**:
A speech engine that reads Chapters aloud.
_Avoid_: Player

**Paragraph**:
A stable text unit inside a Chapter for speech and highlight.
_Avoid_: Page, Block

**Media playback**:
Video, HLS, or DASH display inside a Chapter.
_Avoid_: Player, TTS engine
