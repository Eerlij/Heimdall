# 0008. Runtime i18n with Transloco (en, pt-PT)

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

The UI must support English and European Portuguese, and users switch language in Settings.

## Decision

Use Transloco for runtime language switching from a single build. Locale data for `en` and `pt-PT` is registered for
dates, numbers and currency. The choice is saved on the user profile and mirrored in `localStorage`, so the Keycloak
login page (via `ui_locales`) matches even after logout.

## Consequences

- Switching needs no reload, and there is one artifact to deploy.
- Translations load at runtime (one small request per language).
- Keycloak's pt-PT translations must be checked, and completed in a custom theme if needed.
