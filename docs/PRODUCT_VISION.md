# Bookcast Product Vision

## Purpose

Bookcast is an open-source learning platform for turning long-form written material into high-quality conversational audio and interactive learning material.

The core problem is not text-to-speech and not document parsing.

The problem is making substantial written material practical to learn from when reading is inconvenient, while preserving significantly more of the source than traditional summaries or short AI-generated podcast overviews.

A primary use case is listening while walking, commuting, or doing other activities where reading a screen is impractical.

Another core use case is learning from high-quality material that is not available in the user's native language.

For speakers of smaller languages such as Finnish, much of the best technical, professional, and educational material is available primarily in English. Even when the reader understands English well, consuming complex material in a second language introduces additional cognitive friction.

Bookcast aims to reduce that friction by transforming the material into natural conversational learning content in the user's preferred language.

The initial and default output language is **Finnish**.

## Product promise

Bookcast should let a user provide long-form source material and experience it as a natural, pedagogical conversation between distinct speakers.

The generated experience should remain faithful to the imported source.

It should preserve, where relevant:

- structure
- important arguments
- examples
- explanations
- qualifications
- counterarguments
- trade-offs
- terminology
- progression of ideas

The default goal is **not aggressive summarization**.

Reducing repetition or adapting prose for spoken dialogue is acceptable, but important ideas should not disappear merely to make the result shorter.

The intended experience is not a robot reading translated text aloud.

It should feel closer to listening to two knowledgeable people discuss the material carefully and naturally in the listener's own language.

### Source-grounded, not source-narrated

The conversation should faithfully reflect the imported source while normally discussing the subject matter directly. The default experience is not a book review, reading guide, or narration of source structure.

Avoid unnecessary framing such as “the book says…”, “in this chapter…”, “the author now explains…”, or “the chapter concludes…”. Prefer natural topic-level discussion and transitions.

Explicit attribution to an author, book, study, or source is appropriate when it materially affects meaning, for example to distinguish an author's claim, a study result, or a contested proposition from general explanation.

## Native-language learning

A central Bookcast value proposition is enabling users to learn from source material that is written in another language.

A common initial workflow is:

```text
English long-form source
        ↓
source-grounded transformation
        ↓
natural Finnish dialogue
        ↓
Finnish speech
        ↓
learning in the user's native language
```

This is not intended to be literal sentence-by-sentence translation.

Bookcast should preserve the source's meaning, arguments, examples, qualifications, and technical precision while expressing them as natural spoken Finnish.

Technical terminology should remain in English when that is the established or more natural terminology in Finnish professional usage.

For example, terms such as:

- pull request
- token
- prompt
- API
- code review

may remain in English when translating them would make the explanation less natural or less precise.

The goal is not linguistic purity.

The goal is comprehension.

### Natural Finnish terminology

The target is natural spoken professional Finnish, not literal translation or workplace Finglish. Use established Finnish terminology for ordinary technical concepts when a natural expression exists. Permissions, compute resources, input validation, environment separation, and change risk should normally be expressed naturally in Finnish.

Keep genuinely established domain-specific English terms where Finnish professionals naturally use them. For an important specialized English term without a good established Finnish equivalent, introduce it once with a Finnish explanation and then use consistent natural terminology.

Avoid unnecessary English clauses, awkward English/Finnish hybrids, redundant English term plus Finnish duplicate constructions, and mechanical preservation of English merely because the source is English. A precomputed glossary is not required.

## Why conversational transformation matters

Simply translating a document and feeding the translation into text-to-speech does not solve the full problem.

Long-form material read mechanically can remain difficult and tiring to follow even when the words are technically understandable.

Conversational transformation provides additional pedagogical structure.

For example:

- one speaker can introduce an idea,
- another can explain it in more depth,
- difficult concepts can be unpacked,
- relationships between ideas can be made explicit,
- examples can be discussed instead of mechanically read,
- transitions can be adapted for listening,
- technical terminology can be introduced naturally,
- one speaker can ask the kinds of questions a learner might ask.

The aim is to make complex material easier to absorb without turning it into a shallow summary.

## Language flexibility

Finnish is the initial product default, not a permanent architectural restriction.

Source language and output language are separate concepts.

Bookcast should support both:

```text
sourceLanguage != outputLanguage
```

and:

```text
sourceLanguage == outputLanguage
```

Future users may choose other output languages.

Language selection should therefore belong to the generation request or user preferences rather than be hard-coded into AI-provider integrations.

## Primary user

The initial user is the project's creator.

The first version is intentionally single-user and does not require authentication, authorization, or multi-user isolation.

However, the domain and application architecture should not assume that Bookcast can only ever have one user.

The project should remain capable of evolving into a useful open-source application for other users without requiring a fundamental rewrite.

## Initial source workflow

The initial product does **not** require PDF ingestion.

The first proven use case is simpler:

```text
Create or identify a book
        ↓
Create a chapter
        ↓
Paste chapter text or upload a text file
        ↓
Normalize imported text
        ↓
Generate source-grounded conversational script
        ↓
Generate audio
        ↓
Listen and learn
```

One imported text payload may correspond directly to one chapter.

This is intentional.

Bookcast should not require automatic chapter detection in order to deliver its core value.

### Chapter-first workflow

A Chapter must be independently importable, generatable, and listenable. A normal workflow is:

1. Import one chapter.
2. Generate its podcast.
3. Listen to it.
4. Import another chapter later.

Do not assume the whole Book is available at generation time. This supports ordinary incremental usage and sources that are themselves published incrementally.

Whole-book import and whole-book context may be supported later as optional enrichment. Neither is a prerequisite for generating an individual Chapter.

## Source-format independence

The product domain should not depend on how source material was acquired.

Possible future source inputs include:

```text
pasted text
.txt files
PDF
EPUB
HTML
other structured text sources
```

Each importer should ultimately produce a normalized source representation that downstream generation can consume.

For example:

```text
Text input ──┐
TXT importer ┤
PDF importer ┤
EPUB importer┤
HTML importer┘
       ↓
Normalized source revision
       ↓
Source blocks
       ↓
Generation
```

PDF, EPUB, HTML, clipboard text, and files are acquisition formats.

They are not core generation-domain concepts.

## Imported-source fidelity

Bookcast can only be faithful to information that was actually imported.

For example, a copied chapter may contain references such as:

> “As shown in Figure 10.6...”

while the referenced figure itself was not included in the copied text.

In that case, Bookcast must not invent the missing figure.

The fidelity promise is therefore:

> Generated material should faithfully represent the imported source material.

This is distinct from claiming complete fidelity to the original publication when some material was never imported.

## Initial content model

The first supported source content is primarily prose text.

Future importers may support richer source blocks such as:

```text
Text
Code
Figure
Table
Equation
```

but these are not required initially.

Missing support for non-textual content should be explicit rather than silently reconstructed by AI.

## Conversational audio

The conversational format should feel like a thoughtful discussion rather than two voices taking turns reading paragraphs.

The initial format uses two distinct roles, conceptually similar to:

- a host who guides the discussion, asks questions, and connects ideas
- a knowledgeable second speaker who explains, qualifies, and expands the material

The intended format is expert-led. EXPERT may naturally do most of the talking, including longer stretches of straightforward explanation.

HOST acts as the listener's intelligent guide: opening or redirecting a topic, asking useful follow-up questions, challenging an assumption, requesting clarification or a source-grounded example for difficult material, surfacing a trade-off, and occasionally summarizing or connecting ideas. Intervene when it improves understanding, rather than merely creating more dialogue turns.

Do not require constant back-and-forth or an equal speaker split. Speaker word share is a diagnostic, not a product target; a relatively small HOST share can be entirely appropriate.

Exact personas are a presentation concern and may evolve.

The conversation may restructure prose to make it work in spoken form, but it must not invent unsupported claims or silently replace the author's argument with a different one.

Technical terminology should be preserved when it improves accuracy.

In Finnish output, established English technical terms may remain in English where that is more natural than forced translation.

Literal source code does not generally need to be read aloud line by line. Its purpose and important implications should instead be explained conversationally unless the code itself is essential learning material.

## Long-form fidelity

Bookcast should be capable of producing chapter-length audio rather than only short recaps.

A chapter may result in tens of minutes or more of audio.

Internally, generation should operate on smaller source-grounded units so that:

- omissions can be detected
- individual units can be regenerated
- failures do not invalidate an entire chapter
- source coverage can be measured
- cost can be controlled
- audio can be assembled progressively

The user should nevertheless experience the result as one coherent chapter.

## Generation units

Bookcast distinguishes between three concepts.

### Source block

A stable, normalized part of an imported source revision.

Initially this will usually correspond approximately to a paragraph or another natural text block.

Source blocks provide stable anchors for generated artifacts.

### Script segment

A retryable and regenerable unit of generated conversational material.

A script segment may cover one or more source blocks and contains multiple dialogue turns.

### Dialogue turn

One speaker's utterance inside a script segment.

Dialogue turns may later become useful units for:

- speech synthesis
- transcript synchronization
- seeking
- speaker rendering

These concepts are intentionally not treated as interchangeable.

## Source revisions and provenance

Imported source material should be immutable.

Changing or re-importing source text creates a new source revision rather than silently modifying the source behind already-generated artifacts.

Generated artifacts should be traceable to the source revision used to create them.

Where practical, generated script segments should reference the source blocks they cover.

This enables later questions such as:

- Which source material produced this segment?
- Which source blocks remain uncovered?
- Was an important qualification omitted?
- Which source revision was used for this generation?
- Can this segment be regenerated without losing provenance?

## Listening experience

Mobile listening is a first-class use case.

The eventual player should support:

- play and pause
- reliable resume from the exact previous position
- seeking
- short rewind and forward controls
- playback speed
- chapter navigation
- transcript synchronization or transcript-to-seek where practical
- bookmarks
- lock-screen and headset controls where the platform permits them
- offline listening where practical

A user should not need to regenerate content simply to continue listening.

Generated audio is a persistent artifact.

The exact playback surface remains open.

A PWA is one option, but other mechanisms such as a private podcast feed may later be evaluated before significant player infrastructure is built.

## Learning experience

Audio is the first learning surface, not the final one.

Future learning capabilities may include:

- chapter preparation questions
- quizzes
- flashcards
- explanations of difficult passages
- asking questions about the imported source
- identifying gaps in understanding
- walking-friendly spoken quizzes
- returning from an answer to the relevant source or transcript position

These features should remain grounded in the imported source rather than becoming generic tutoring detached from the material.

## Source grounding

The imported source revision is authoritative for generated learning content.

Generation and evaluation should eventually make it possible to answer questions such as:

- Which source blocks contributed to this script segment?
- Which important source ideas have not yet been covered?
- Did the generated script introduce unsupported claims?
- Was an important qualification omitted?
- Does the generated sequence still reflect the source's argument?

Coverage is more important than arbitrary output length.

## AI provider independence

Bookcast is not an OpenAI, Anthropic, Google, ElevenLabs, or other vendor-specific product.

Different capabilities may use different models or providers.

Provider choice should be replaceable and measurable.

The product should make it possible over time to compare providers based on characteristics such as:

- source fidelity
- conversational quality
- Finnish-language quality
- speech quality
- terminology handling
- latency
- reliability
- cost

Model choice is an implementation concern, not a product identity.

## Generation quality

For the initial Finnish learning experience, generation quality has at least four separate dimensions.

### Source fidelity

Does the generated conversation preserve the important ideas, examples, qualifications, and trade-offs of the imported material?

### Natural Finnish

Does the dialogue sound like natural spoken Finnish rather than a literal translation of English sentence structure?

### Terminology

Are technical terms handled in a way that a Finnish professional or learner would naturally understand?

### Pedagogical quality

Is the conversational version genuinely easier to understand and retain than mechanically listening to the source or a literal translation?

These dimensions should eventually be evaluated separately.

## Generation progress

Long chapters and books may require substantial processing.

Generation should therefore eventually be incremental.

A user should be able to observe meaningful progress rather than one opaque operation.

Failures should be recoverable from the smallest practical generation unit.

The initial implementation does not need sophisticated distributed workflow infrastructure.

## First product experiment

Before finalizing the production generation abstraction, Bookcast should run a small real-model experiment using representative source material.

The initial experiment should use:

- a real long-form chapter
- preferably an English technical or professional source
- Finnish output
- two conversational speakers

The purpose is to learn:

- practical source-unit size
- how much previous conversational context later segments need
- what structured output works reliably
- how source-block references should be represented
- how much source material is lost during transformation
- how long generated dialogue becomes relative to its source
- what information the generation port actually needs
- how natural the Finnish output is
- whether technical terminology is handled appropriately
- whether the result is genuinely easier to learn from than mechanical reading

This experiment informs the generation contract.

It is not production architecture.

## Initial vertical slice

After the generation experiment, the first useful application slice is:

```text
Create Book
    ↓
Create Chapter
    ↓
Paste text or upload .txt
    ↓
Normalize into stable source blocks
    ↓
Preview imported source
    ↓
Request Finnish podcast generation
    ↓
Generate multiple structured script segments
    ↓
Display two-speaker script
```

The first production implementation may use a fake generation adapter after the real-model spike has informed the shape of the generation contract.

The fake adapter should support deterministic failure injection so retry and failure semantics can be tested.

## Near-term progression

A likely progression is:

```text
text import
    ↓
normalized source model
    ↓
real-model Finnish generation spike
    ↓
fake-adapter production slice
    ↓
real text-generation adapter
    ↓
source coverage evaluation
    ↓
Finnish speech synthesis
    ↓
chapter audio assembly
    ↓
persistent playback progress
    ↓
mobile listening experience
```

The exact order may change based on what is learned from implementation.

## Non-goals for the initial product

Bookcast is initially not:

- a public podcast hosting network
- a social network
- a podcast discovery service
- an audiobook marketplace
- an ebook reader replacement
- a generic text-to-speech utility
- a generic translation service
- a short-summary generator
- a general-purpose document parser
- an OCR platform
- a model-training platform
- a system for building proprietary foundation models
- a microservice platform
- a multi-tenant SaaS platform

Do not build these capabilities speculatively.

## Copyright and source ownership

The initial product is oriented toward private processing of material the user is entitled to use.

Public redistribution of generated adaptations of copyrighted material introduces separate licensing and rights questions and is outside the initial product scope.

Bookcast should not assume that importing material grants redistribution rights.

Source text may contain copyrighted material and must not be written to application logs by default.

## Success

The product succeeds when a user can take substantive long-form source material and learn from it through natural conversational audio without feeling that the important substance disappeared during conversion.

A useful question is:

> After listening to the Bookcast version of a chapter, does the user understand substantially the same arguments, examples, qualifications, and trade-offs that were present in the imported source?

For cross-language learning, another important question is:

> Did Bookcast make material that was cognitively demanding in the source language feel natural to learn from in the listener's own language without sacrificing its substance?

The goal is not to minimize listening time.

The goal is to make deep, source-faithful learning possible in situations and languages where reading the original material is less convenient or less natural.