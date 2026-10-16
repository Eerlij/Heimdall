# 0009. Hand-written TypeScript API types

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

The Angular frontend consumes the Spring API. Types could be generated from OpenAPI or written by hand.

## Decision

TypeScript request and response types are written by hand. springdoc still publishes OpenAPI docs in the dev profile
for reference.

## Consequences

- Full control over frontend models, with no generator tooling.
- **Accepted risk:** types can drift from the backend silently. Only end-to-end tests catch a mismatch, so every API
  change must update the frontend types in the same pull request.
