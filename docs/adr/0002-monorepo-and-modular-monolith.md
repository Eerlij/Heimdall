# 0002. Monorepo and modular monolith

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Heimdall starts as a personal app that may open to the public later. It will grow many features (todos, expenses,
wishlists, imports, recommendations) maintained by a very small team.

## Decision

- One repository holding `backend/`, `frontend/`, `infra/` and `docs/`.
- One Spring Boot application split into feature modules with Spring Modulith. Module boundaries are verified by a
  test (`ApplicationModules.verify()`), and modules communicate through application events backed by the persistent
  event publication registry.

## Consequences

- One deploy, one database and simple local development, with boundaries still enforced by the build.
- A module can be extracted into its own service later if it ever needs independent scaling.
- Every module shares one JVM and release cycle.
