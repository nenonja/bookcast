# ADR-0004: Make AI providers replaceable

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Bookcast depends heavily on AI capabilities, but no individual vendor or model should become part of the product's core architecture.

Different providers may be best for different tasks.

For example:

- one model may produce the best Finnish long-form dialogue
- another may provide cheaper coverage verification
- a third provider may offer better speech synthesis
- some users may prefer local models

AI model quality, pricing and availability change rapidly.

Provider-specific APIs also change independently.

Binding application logic directly to one vendor would make experimentation and migration unnecessarily expensive.

The same principle applies to development infrastructure: the codebase should remain understandable by different coding agents rather than depend on one vendor-specific workflow.

## Decision

Model AI integrations as **capabilities behind application ports**.

Initial capability categories include:

```text
TextGenerationPort
SpeechSynthesisPort
EmbeddingPort
TranscriptionPort
```

Additional ports may be introduced when a genuinely distinct capability is required.

Application and domain modules must not depend directly on provider SDKs.

Provider integrations are outbound adapters.

Example:

```text
generation.application
        ↓
TextGenerationPort
        ↓
OpenAiTextGenerationAdapter
AnthropicTextGenerationAdapter
GeminiTextGenerationAdapter
LocalModelTextGenerationAdapter
```

Speech synthesis is independently selectable:

```text
SpeechSynthesisPort
        ↓
OpenAiSpeechAdapter
ElevenLabsSpeechAdapter
```

A single generation workflow may use different vendors for different capabilities.

## Provider configuration

Provider and model selection must be configuration-driven rather than hard-coded.

A generation profile may define values such as:

```text
script provider
script model

coverage judge provider
coverage judge model

host speech provider
host voice

developer speech provider
developer voice
```

Selection may eventually occur:

- globally
- per user
- per book
- per chapter
- per generation run

The initial implementation should remain simpler unless a use case requires all of these levels.

## Capability awareness

Provider abstraction must not be reduced to the smallest common denominator.

The system may retain provider/model capability metadata such as:

```text
structured output support
context window
vision support
PDF support
streaming support
estimated cost class
language quality
```

Application routing may later use these capabilities.

## Observability

For every AI-generated artifact where practical, persist:

- provider
- model
- model/version identifier where available
- prompt/template version
- generation timestamp
- latency
- token usage where available
- estimated cost where available
- retry count
- regeneration count
- voice/provider metadata for audio

This data enables meaningful model comparison rather than anecdotal preference.

## Consequences

### Positive

- Models and vendors can be changed with limited architectural impact.
- Different providers can be used for different tasks.
- Supports experimentation and A/B comparisons.
- Local models can be introduced later.
- Vendor outages and model deprecations are easier to handle.
- Cost and quality can be compared empirically.

### Negative

- Additional abstraction and configuration complexity.
- Different vendors expose different capabilities and semantics.
- Fully interchangeable behavior cannot be guaranteed.
- Testing adapters requires provider-specific integration tests.
- Cost metadata may not be consistently available across vendors.

## Rules

- No vendor SDK imports in domain or application packages.
- Vendor names must not appear in core domain concepts.
- Provider-specific request and response models stay inside adapters.
- Domain/application code depends on Bookcast-owned interfaces.
- Adding a new provider must not require changing core domain logic.
- Provider fallback behavior must be explicit rather than silently changing model behavior.

## Alternatives considered

### Use only OpenAI initially and abstract later

Rejected because provider independence is a known architectural requirement from the beginning and is inexpensive to preserve with ports/adapters.

### Use one generic AI gateway abstraction everywhere

Partially rejected.

A shared library such as Spring AI may be used inside infrastructure adapters, but Bookcast still owns its application-level ports.

This prevents Spring AI itself from becoming part of the domain contract.

---

