# Engineering Principles

These principles guide how Bookcast is designed and evolved.

They are heuristics, not architectural decisions.

Architecture Decision Records explain concrete decisions made for this project. These principles help humans and coding agents reason about new decisions, but they do not replace human judgment.

## 1. Context is infrastructure

Important project knowledge must not live only in chat history, individual developers' memory, or external tools.

Prefer repository-local, machine-readable context:

- `AGENTS.md` for concise working instructions
- ADRs for architectural decisions and trade-offs
- OpenAPI for API contracts
- Spring Modulith for module structure
- architecture tests for enforceable constraints
- executable build and test configuration

Documentation should explain intent. Machine-readable structures should describe facts whenever possible.

## 2. The dependency graph must tell the truth

Code structure should make important dependencies explicit.

A module boundary that exists only in a diagram is weaker than one the build can verify.

Prefer:

- explicit module APIs
- declared dependencies
- no hidden access to module internals
- ports between application logic and infrastructure
- automated cycle and boundary checks

The dependency graph should help answer both:

1. Where should a change be made?
2. What can that change affect?

## 3. Architecture rules should become executable

When an architectural mistake reveals a reusable invariant, encode that invariant in tooling where practical.

Use the progression:

`Violation -> Understand -> Codify -> Enforce`

Possible enforcement mechanisms include:

- Spring Modulith verification
- ArchUnit
- jMolecules
- compiler/type-system constraints
- OpenAPI compatibility checks
- CI policies

Do not create theoretical rules for problems the project has never encountered unless the invariant is fundamental to the chosen architecture.

## 4. Prefer honest feedback over fast feedback

Fast CI is useful only when its results can be trusted.

Tests should distinguish between:

- deterministic domain correctness
- integration correctness
- provider contract correctness
- generative AI quality and source fidelity

A test generated from the same misunderstanding as the implementation is not independent verification.

Do not optimize CI speed at the expense of trustworthy results.

## 5. AI output requires independent verification

Generative output is probabilistic.

Do not use the generating model's own output as the sole proof of correctness.

For book-to-podcast generation, verification should eventually consider at least:

- source coverage
- unsupported claims
- preservation of important examples and qualifications
- chapter ordering
- speaker/script structural validity

Where practical, verification should be based on the original source rather than on the generated script alone.

## 6. Models and vendors are replaceable; the system is the product

No AI vendor is part of the Bookcast domain.

Models evolve quickly. The surrounding system should make models useful safely and economically.

Keep separate capabilities for:

- text generation
- speech synthesis
- embeddings
- transcription
- evaluation

Record enough metadata to compare providers empirically.

Do not select a vendor merely because it is already integrated.

## 7. Optimize the whole feedback loop

Do not optimize one component while ignoring the system around it.

Useful measurements may eventually include:

- generation latency
- retries
- regeneration rate
- model/provider cost
- source coverage
- test reliability
- CI feedback latency

Activity is not the same as value.

More generated text, commits, PRs or audio hours do not automatically mean a better product.

## 8. Prefer small, verifiable iterations

Long-running workflows should be decomposable into independently retryable stages.

For example:

`source -> chapter -> source chunk -> script segment -> audio segment -> episode`

A failure in one segment should not require regenerating an entire book.

Persist useful intermediate artifacts where doing so improves reproducibility, debugging or cost.

## 9. Build vertical slices before speculative infrastructure

Architecture should support future growth without requiring us to build that future immediately.

Prefer the smallest end-to-end slice that validates a product assumption.

For Bookcast, an early slice is:

`pasted text or .txt -> chapter -> structured two-speaker script -> UI`

Add infrastructure when a demonstrated requirement justifies it.

## 10. Human judgment owns intent and trade-offs

Coding agents can propose implementations, tests, refactorings and architectural options.

They do not own the decision about:

- what should be built
- which trade-off is acceptable
- where a domain boundary belongs
- whether complexity is justified
- what product behavior is correct

ADRs should capture human decisions and their rationale.

Agents may challenge an ADR or propose a new one, but they should not silently redefine architectural intent.

## 11. Repository context must be vendor-independent

A new contributor or coding agent should be able to understand the project from the repository itself.

Do not rely on:

- a specific previous ChatGPT conversation
- Claude-specific memory
- Codex-specific hidden context
- one developer's IDE state

The repository is the durable source of project context.

## 12. Improve the system after failures

When something repeatedly wastes developer or agent effort, look for a systemic fix rather than another prompt workaround.

Examples:

- repeated wrong module selection -> improve module/context metadata
- repeated architectural violation -> add an architecture rule
- repeated flaky failure -> fix or quarantine the test
- repeated provider-specific leakage -> strengthen the port boundary
- repeated generation omission -> improve the coverage/evaluation stage

The goal is not to make one agent session succeed.

The goal is to make future sessions less likely to fail.