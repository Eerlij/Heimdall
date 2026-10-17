# 0003. PostgreSQL with Flyway migrations

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

The data is relational (money, todos, users) with some flexible parts (imported content). Recommendations may later
need vector search.

## Decision

PostgreSQL 18 is the only database. Every schema change is a versioned Flyway migration, and Hibernate only
validates (`ddl-auto: validate`). Every user-owned table has an indexed `user_id`. Timestamps are `timestamptz` in
UTC (Java `Instant`/`OffsetDateTime`, enforced by an ArchUnit rule). Keycloak gets its own database and role on the
same server.

## Consequences

- JSONB and pgvector are available when needed.
- Schema history is reviewable and reproducible.
- Library-owned tables (for example Spring Modulith's `event_publication`) need their own migrations, which must be
  updated on library upgrades.
