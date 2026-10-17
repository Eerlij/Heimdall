# 0004. Keycloak with the Backend-for-Frontend pattern

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Users sign in with email and password today, and maybe social login or passkeys later. Tokens stored in browser
JavaScript can be stolen through XSS.

## Decision

Keycloak (self-hosted OIDC) manages identities. Spring Boot is the OIDC client (BFF): it performs the login and gives
the browser only an HttpOnly session cookie plus a CSRF token. The API is authenticated by the Spring session (stored
in Postgres via Spring Session JDBC). Logout is RP-initiated at Keycloak, and Keycloak back-channel logout ends the
Spring session. Self-registration is enabled in dev only until the GDPR self-service features exist.

## Consequences

- No token is reachable from JavaScript, following current IETF guidance for browser-based apps.
- Keycloak is another service to run, patch and monitor.
- The browser, Keycloak and Spring must share one origin per run mode (see [ADR 0010](0010-container-first-run-modes.md)).
