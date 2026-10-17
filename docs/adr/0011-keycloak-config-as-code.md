# 0011. Keycloak configuration as code

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Keycloak's `--import-realm` skips realms that already exist, so it cannot apply later changes (for example turning
registration off in production).

## Decision

Manage the realm with keycloak-config-cli, run as a one-shot container in every mode. Per-environment values (redirect
URIs, registration on or off, SMTP) come from environment variables, and no secret lives in the config files.

## Consequences

- Realm changes are reviewed in pull requests and applied idempotently.
- keycloak-config-cli must be kept compatible with the Keycloak version.
