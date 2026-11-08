# Library

What the user collects, and how progress on it is tracked.

## Language

**Entry**:
A novel work saved in the library. The code row is frozen by ADR-0001.
_Avoid_: Novel (for the row), Manga (in product copy), Entry as a code name (no such class)

**Chapter**:
One loadable text unit of an Entry.
_Avoid_: Page, Episode

**Category**:
A user bucket for Entries, scoped to all, manga, or novels, with a system Uncategorized bucket.
_Avoid_: Folder, Tag

**Library**:
The set of favorite Entries viewed as a list.
_Avoid_: Favorite (as the UI term), Library table (there is no table)

**History**:
The last-read marker for a Chapter.
_Avoid_: Reading session

**Reading session**:
Recorded reading time for a Chapter, used for statistics.
_Avoid_: History

**Track**:
The link between an Entry and an external tracker service.
_Avoid_: Tracker (for the row), sync

**Section**:
A volume or paged grouping of Chapters inside an Entry.
_Avoid_: Page, chapter group
