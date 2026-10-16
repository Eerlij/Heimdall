# 0006. Observability with OpenTelemetry, Grafana LGTM and Faro

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

We want logs, metrics, traces, dashboards and alerts that are vendor-neutral and self-hostable, covering both the
backend and the browser.

## Decision

- The backend exports traces, metrics and logs over OTLP to Grafana Alloy, which forwards them to Loki, Tempo and
  Prometheus. Grafana provides dashboards and alerts, all committed as code.
- The browser reports errors, Web Vitals and traces with Grafana Faro to a same-origin `/faro` endpoint.
- **Faro runs without consent.** It is treated as strictly necessary to keep the service working and secure: it is
  first-party (same origin, self-hosted), uses no cookies, keeps its session id in `sessionStorage` only, collects no
  personal data, and is never used for marketing or profiling.
- Logs never contain tokens, emails, monetary amounts or request bodies. Users appear only by internal id.

## Consequences

- One trace links a browser action to backend and database work.
- Running the LGTM stack costs resources; dev uses the single `grafana/otel-lgtm` container.
- The Faro justification must be revisited if its configuration ever collects more than described here.
