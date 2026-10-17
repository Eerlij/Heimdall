# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Heimdall is built in stages over a long time. The reasons behind early choices (stack, auth model, observability)
are easy to forget and expensive to rediscover.

## Decision

Every significant architecture decision is recorded as an ADR in `docs/adr/`, named `NNNN-kebab-title.md`, using the
lightweight MADR-style format in [0000-template.md](0000-template.md) (Status, Context, Decision, Consequences).
Accepted ADRs are immutable: a changed decision gets a new ADR that supersedes the old one.

## Consequences

- Decisions and their trade-offs are reviewable in pull requests alongside the code.
- Writing an ADR adds a small cost to every significant decision.
