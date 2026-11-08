# Translation & AI

How chapter text and metadata are translated or summarized.

## Language

**Translation purpose**:
Which text is translated: chapter, entry metadata, or a browse title.
_Avoid_: Engine (for a purpose)

**Translation engine**:
A translation provider option such as a free tier, an LLM, Libre, DeepL, or Google Cloud.
_Avoid_: Provider (reserved for AI provider definitions)

**AI provider**:
A user-defined endpoint, header, and model used for LLM translation.
_Avoid_: Engine

**User guidelines**:
User prompt rules scoped to an Entry.
_Avoid_: System prompt

**Translated chapter**:
A stored translation of one Chapter for one target language.
_Avoid_: Translation database

**Chapter summary**:
An AI summary of one Chapter.
_Avoid_: Translation
