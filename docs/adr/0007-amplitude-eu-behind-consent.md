# 0007. Amplitude analytics in the EU region, behind consent

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

We want product analytics (page views, feature usage). Users are in the EU, so GDPR and ePrivacy apply.

## Decision

Use the Amplitude Browser SDK with `serverZone: 'EU'`. The SDK is loaded only after the user opts in through a consent
banner; declining means it is never loaded. Users are identified by their internal UUID, never by email or name.
Events come from a typed catalogue.

## Consequences

- A consent banner is required, and the choice is changeable in Settings.
- The Amplitude project must be created in the EU region; it cannot be migrated later.
- Data goes to a third-party processor, which needs a data processing agreement and a mention in the privacy policy.
