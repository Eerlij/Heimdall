# 0010. Container-first with three run modes

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

The production host is undecided. OIDC with a BFF needs the browser, Spring and Keycloak to agree on a single public
origin.

## Decision

Everything runs in containers orchestrated by Docker Compose, and every mode is browsed through nginx:

- `dev`: compose infrastructure, with the backend and `ng serve` running on the host behind nginx.
- `full`: everything in containers with the production frontend bundle (service worker, push, E2E tests).
- `tunnel`: `full` exposed through a Cloudflare named tunnel for real-phone testing.

Keycloak's public URL and Spring's OIDC endpoints are configured per mode through environment variables.

## Consequences

- Any container host works later.
- Phone testing waits until a domain exists: quick tunnels' random hostnames can't match Keycloak's fixed hostname.
