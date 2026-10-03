# ADR-0006: Model source content independently from import format

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Bookcast was initially discussed as a system that accepts PDF books, extracts their chapter structure, and generates podcast-style learning material.

Practical usage demonstrated that PDF ingestion is not required to deliver the product's core value.

A successful workflow already exists where a chapter is copied from a digital book service and provided as plain text.

In that workflow:

- one copied text input corresponds to one chapter,
- automatic chapter detection is unnecessary,
- images and some non-text content may be missing,
- the resulting conversational adaptation is still useful.

PDF extraction introduces a separate set of difficult problems:

- PDFs do not reliably encode semantic structure,
- reading order can be ambiguous,
- chapter outlines may be absent,
- multi-column layouts can corrupt extraction,
- scanned documents require OCR,
- code, tables, figures, equations and footnotes require specialized handling.

These problems are important for future import support but are not the core product problem.

The core product problem is transforming long-form source material into faithful conversational learning content.

If the generation domain is built around PDF-specific concepts such as page numbers, PDF chapters or extraction structures, later support for pasted text, EPUB, HTML and other source formats becomes unnecessarily difficult.

Generated artifacts also require stable provenance. A script segment must be able to identify which imported source material it was generated from even if the same chapter is later imported again or extraction behavior changes.

## Decision

Bookcast models **normalized source content independently from its acquisition format**.

The initial supported acquisition methods are:

- pasted plain text,
- uploaded `.txt` files.

One import may correspond directly to one Chapter.

Automatic chapter detection is not required.

Future source formats such as PDF, EPUB and HTML will be implemented as import adapters that produce the same normalized source model.

## Source model

The initial model uses the following concepts:

```text
Book
  ↓
Chapter
  ↓
SourceRevision
  ↓
SourceBlock[]
```

### Book

A Book represents a work that the user wants to learn from.

A Book is not synonymous with a file.

### Chapter

A Chapter represents an ordered learning unit within a Book.

Chapter structure may initially be entered manually.

### SourceRevision

A SourceRevision is an immutable imported version of the source material for a Chapter.

Imported source text is never silently mutated after generated artifacts depend on it.

Editing or re-importing source material creates a new revision.

A SourceRevision should carry enough identity information to support reproducibility, such as:

- a Bookcast-owned identifier,
- creation/import time,
- source content hash,
- importer/version metadata where useful.

The exact persistence representation is an implementation detail.

### SourceBlock

A SourceBlock is a stable ordered unit within a SourceRevision.

The initial representation is text-oriented and is expected to use natural paragraph-level blocks where practical.

Each block has a stable identity within its SourceRevision.

Generated artifacts reference SourceBlocks rather than format-specific locations such as PDF page numbers.

Optional importer-specific location metadata may still be retained for display or debugging.

## Source provenance

Generated artifacts must identify the SourceRevision from which they were produced.

Script-generation units should retain source-block references sufficient to determine which source material they cover.

This enables future:

- coverage evaluation,
- regeneration,
- debugging,
- provenance display,
- comparison between source revisions.

## Import adapters

Source acquisition is an adapter concern.

Conceptually:

```text
Pasted text ─┐
TXT ─────────┤
PDF ─────────┤
EPUB ────────┤
HTML ────────┘
       ↓
Source importer
       ↓
SourceRevision + SourceBlocks
```

Downstream generation code must not require knowledge of the acquisition format.

## Initial text normalization

The first implementation supports primarily prose text.

Normalization should preserve meaningful paragraph ordering and content.

It should avoid transformations that make source references unstable without a clear benefit.

Advanced support for:

- figures,
- tables,
- equations,
- images,
- layout,
- OCR,
- executable code structure

is deferred.

## Missing source material

Bookcast's fidelity guarantee applies to **imported source material**.

If the original publication contains a figure, table, image or other information that is not present in the import, Bookcast must not infer or invent its contents.

References to missing material may remain visible to generation.

The system may later detect and communicate missing referenced content explicitly.

## Consequences

### Positive

- The initial product can start from the already-proven paste/text workflow.
- PDF parsing does not block validation of the generation product.
- Generation remains independent from document format.
- PDF, EPUB and HTML support can be added later as adapters.
- Stable source identity exists before generated artifacts depend on it.
- Source coverage can use format-neutral block identifiers.
- Re-importing or changing extraction does not silently invalidate provenance.
- The architecture reflects the actual product value rather than a specific input technology.

### Negative

- Users initially create chapter structure manually.
- Copy/pasted content may omit figures, tables and other publication elements.
- Source blocks add identity and persistence complexity beyond storing one large text string.
- Immutable source revisions consume additional storage.
- Importers must translate their native structures into the normalized Bookcast model.

## Alternatives considered

### PDF-first domain model

Rejected.

PDF is an acquisition format rather than a core product concept.

Building generation around PDF pages, outlines or extraction structures would couple the product to one unreliable input representation.

### Store only one mutable text value per Chapter

Rejected.

This makes provenance and regeneration ambiguous once source text changes.

Generated artifacts would no longer have a stable definition of the material from which they were produced.

### Store raw text with character offsets only

Not selected as the primary provenance mechanism.

Character offsets are fragile when normalization or re-import occurs.

Stable SourceBlock identifiers provide a more durable format-neutral anchor.

Character offsets may still be used as optional metadata inside a block if later useful.

### Require automatic chapter detection

Rejected for the initial product.

The current use case already supplies one chapter at a time.

Automatic chapter detection adds substantial complexity without being necessary to validate Bookcast's core value.

## Deferred decisions

This ADR does not decide:

- PDF parsing technology,
- EPUB parsing technology,
- OCR,
- automatic chapter detection,
- rich multimedia source blocks,
- manual source-block editing,
- importer confidence scoring,
- exact content-hash algorithm,
- exact SourceBlock granularity for every source type.

These decisions should be made when their use cases become real.