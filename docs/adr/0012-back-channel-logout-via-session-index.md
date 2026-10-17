# 0012. Back-channel logout via Spring Session's principal index

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

Spring Security's default `InMemoryOidcSessionRegistry` keeps the link between a Keycloak session and our HTTP session
in memory. Sessions are stored in JDBC and survive a backend restart, but the links do not, so a Keycloak sign-out after
a restart would no longer end the matching session.

## Decision

Resolve sessions through Spring Session's principal index (`SessionStoreOidcSessionRegistry`) instead of a registry of
our own. The principal name is the Keycloak `sub`, so a logout token finds its sessions directly in the session store.
No extra table is needed.

## Consequences

- Logout tokens must carry `sub`. Keycloak 26.8 always sends it (plus `sid` when `backchannel.logout.session.required`
  is set). Tokens with only `sid` are ignored, which is stricter than Spring's in-memory registry that matches by `sid`
  alone.
- The principal name must stay `sub`.
- Sessions of the token's subject that cannot be deserialized are deleted (fail closed).
- Spring's back-channel handler re-posts to `{baseScheme}://localhost{basePort}/logout/connect/back-channel/{registrationId}`
  with the raw session id as the `SESSION` cookie, so base64 cookie encoding is disabled and session cookies are no
  longer base64-encoded.
- Upstream limits remain: the handler's `RestTemplate` has no timeouts, and the JWK set is re-fetched on every re-post.
- A Keycloak sign-out while the backend is down is lost (Keycloak sends it once); that session lives until its idle
  timeout.
- Changing a class stored in the session (for example `AuthenticatedUser`) means purging `spring_session`.
- Moving sessions to Redis keeps this behaviour only with the indexed repository
  (`spring.session.redis.repository-type=indexed`).
