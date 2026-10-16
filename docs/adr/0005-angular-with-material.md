# 0005. Angular with Angular Material

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

The frontend is a TypeScript, mobile-friendly, installable web app. It needs per-page titles, loading states,
lazy-loaded features, a custom 404 and accessibility.

## Decision

Use the latest Angular (standalone components, signals, zoneless, OnPush, lazy routes) with Angular Material + CDK
and a custom Material 3 theme supporting light, dark and system modes. The accessibility target is WCAG 2.2 AA.

## Consequences

- Routing titles, the service worker (PWA), i18n hooks and accessibility primitives come from the framework.
- The default Material look needs a custom theme to feel like Heimdall.
