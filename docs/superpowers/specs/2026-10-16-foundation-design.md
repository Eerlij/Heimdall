# Heimdall — Foundation Design

## Context
Heimdall ("a bridge to all the dimensions of life") is a personal life-assistant: expenses, wishlists, todo lists,
imports from social apps (e.g. recipes), recommendations from the user's own data, and alerts/reminders.
The repo is empty (README + `.claude/settings.json`). We plan **bottom up**: this document covers only the
**foundation**, the production-grade skeleton every feature module plugs into. Each feature (todos + reminders
first, then expenses, ...) gets its own design later.

Bar set by the user: clean, performant, maintainable; observability & monitoring, logging, documented code, custom
404, good UI/UX, a meta title per page, loading states, analytics; industry best practice.

**Execution step 1 after approval:** copy this design to `docs/superpowers/specs/2026-10-16-foundation-design.md`,
commit it, then write the implementation plan (superpowers:writing-plans).

## Goals / non-goals / anti-goals
- **Goals:** a deployable skeleton with login/logout, app shell, settings, push plumbing, error pages; full
  observability; analytics behind consent; strict CI; documentation conventions from the first commit.
- **Non-goals (this milestone):** feature modules; recommendations; social imports; choosing a production host;
  prod backups and SMTP; native apps; GDPR self-service (privacy/terms pages, account deletion, data export). These
  are deferred, so **self-registration stays off in production until they exist** (on in dev only).
- **Anti-goals:** tokens reachable by JavaScript; PII, tokens or financial data in logs/analytics; Amplitude firing
  before consent; a module reaching into another module's internals; any secret committed (**the repo is public**).

## Decisions made by the user
| Area | Decision |
|---|---|
| Audience | Personal first, public later: every user-owned row has `user_id` from day one |
| Platform | Responsive web app, installable PWA, Web Push |
| Frontend | Angular (TypeScript) |
| Backend | Spring Boot **modular monolith** |
| Build | Maven |
| DB | PostgreSQL + Flyway |
| Auth | Keycloak, **BFF**: Spring is the OIDC client; the browser holds only an HttpOnly session cookie + CSRF token |
| Sign-up | Open self-registration with email verification + password reset, **enabled in dev only** until GDPR pieces exist |
| Hosting | Undecided, so container-first |
| Observability | OpenTelemetry → Grafana LGTM (Loki, Tempo, Prometheus, Grafana) |
| Frontend monitoring | Grafana Faro, **always on** as strictly necessary; ADR records the justification |
| Analytics | Amplitude, **EU data region**, only after opt-in consent |
| i18n | English + **pt-PT**, runtime switching (Transloco), saved on the user profile |
| API contract | **Hand-written** TS types (drift risk accepted) |
| CI / repo | GitHub Actions, **public repo** |
| Quality | Strict and enforced in CI |
| Phone testing | Cloudflare **named tunnel** on a domain the user will buy. Until then, desktop-only push testing (Claude dropped the quick-tunnel fallback: its random hostname can't match Keycloak's fixed hostname or redirect URIs) |
| Milestone UI | Login/logout + app shell + empty dashboard; Settings; push plumbing with "send test notification"; error pages |

## Calls made by Claude (please check)
- Angular Material + CDK as the UI kit, with a custom Material 3 theme.
- nginx serves the Angular bundle and reverse-proxies Spring, Keycloak and the Faro collector, so there is **one origin**.
- Spring Session JDBC, Spring Data JPA (`open-in-view=false`), RFC 9457 errors, springdoc (dev only), virtual threads,
  graceful shutdown, WCAG 2.2 AA, Dependabot.
- Dev mail: a Mailpit container catches Keycloak verification and reset mails.
- No public pages in this milestone: everything except error pages requires login, so the consent banner appears after
  first login.
- Keycloak uses its own `keycloak` database on the same Postgres server; the app uses `heimdall`.
- **Realm config as code via keycloak-config-cli** (one-shot container, idempotent, every mode). `--import-realm` skips existing realms, so it can't manage prod. Per-env values (redirect URIs, registration on/off, SMTP) come from env vars.
- The last-used language is also kept in `localStorage` (a functional preference, not tracking) so `ui_locales` is correct on the login page after logout. After login, the profile is the source of truth.

## Architecture
```
Browser (Angular PWA) ──one origin──> nginx
   ├─ /                      static Angular bundle (SPA fallback to index.html)
   ├─ /api, /oauth2, /login, /logout  ──> Spring Boot (BFF + API) ──> Postgres (heimdall db: app data + sessions)
   ├─ /auth                  ──> Keycloak (KC_HTTP_RELATIVE_PATH=/auth) ──> Postgres (keycloak db)
   └─ /faro                  ──> Alloy (Faro receiver)
Spring <──back-channel (internal URLs: token/JWKS one way, logout the other)──> Keycloak        Browser ──(after consent)──> Amplitude EU
Spring, Keycloak ──OTLP──> Alloy ──> Loki / Tempo / Prometheus ──> Grafana (dashboards + alerts)
```

### Run modes
**Every mode is browsed through nginx**, so there is exactly one origin per mode and Keycloak's public URL
(`KC_HOSTNAME`) and Spring's authorization/logout URIs are set per mode from env.

| Mode | Origin | How | Used for |
|---|---|---|---|
| `dev` | `http://localhost` | `docker compose up` (nginx, Postgres, Keycloak, Mailpit, otel-lgtm). nginx proxies `/` → `ng serve` on the host (`host.docker.internal:4200`, websocket for HMR) and `/api`… → Spring on the host (`mvn spring-boot:run`) | Daily coding with hot reload. **No service worker**: Angular only enables it in production builds |
| `full` | `http://localhost` | `docker compose --profile full up`: nginx serves the production bundle; backend runs in a container | Push/PWA testing on desktop (localhost counts as a secure context), Playwright E2E (locally + CI) |
| `tunnel` | `https://dev.<domain>` | `full` + `cloudflared` named tunnel, with the env switched to the tunnel origin | Real-phone testing (iOS requires an installed PWA for push) |

### Repo layout (monorepo)
```
backend/    Maven Spring Boot app
frontend/   Angular CLI workspace
infra/      docker-compose.yml (+ profiles), nginx config, keycloak-config-cli realm config, Alloy config,
            Grafana dashboards + alert rules as code, .env.example
docs/adr/   one ADR per decision above
.github/    workflows, dependabot.yml
```

### Backend
- Java **25 LTS**, latest Spring Boot **4.x** GA, matching Spring Modulith (exact versions verified at scaffold time).
- **Modules** (Spring Modulith, top-level packages): `identity`, `settings`, `notifications`, `shared`.
  `ApplicationModules.verify()` test; modules talk through application events.
- **Auth (BFF):**
  - `oauth2-client` with an explicit provider config: browser-facing endpoints on the **public** Keycloak URL, token/JWKS on the **internal** URL; issuer = public URL; Keycloak `KC_HOSTNAME` = public URL + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`.
  - The API is authenticated by the **Spring session only**. Keycloak tokens are used at login and not afterwards (no refresh, no relay to the browser). The Spring session has its own idle timeout (configurable, default 30 min).
  - **Back-channel logout:** Keycloak calls Spring's OIDC back-channel logout endpoint when a user's Keycloak session ends (logout elsewhere, admin signs the user out), which kills the Spring session.
  - `server.forward-headers-strategy=framework` so `redirect_uri` uses the browser's origin behind nginx/tunnel.
  - **`/api/**` returns 401** when unauthenticated (`HttpStatusEntryPoint`), never a 302 to Keycloak (which would surface in Angular as a CORS status-0 error). Only browser navigation to `/oauth2/authorization/keycloak` starts login.
  - **Logout:** a custom `LogoutSuccessHandler` invalidates the session and redirects to Keycloak's `end_session_endpoint` (configured explicitly, because the manual provider config skips discovery) with `id_token_hint` + `post_logout_redirect_uri`.
  - CSRF: Spring Security SPA CSRF config (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header, read natively by Angular `HttpClient`).
  - Cookies `HttpOnly`, `Secure` (except plain-http localhost), `SameSite=Lax`.
  - Login passes `ui_locales` so Keycloak pages and mails match the app language.
  - On first login, upsert an `app_user` row keyed by Keycloak `sub` (UUID).
- **Persistence:** Flyway for every change; all timestamps are `timestamptz` in UTC; `user_id` FK + index on owned tables.
- **Errors:** RFC 9457 `ProblemDetail` via a global `@RestControllerAdvice`.
- **Push:** Web Push with VAPID (keys + `mailto:` subject from env); `push_subscription` table per user/device; deletes subscriptions that expired (404/410); `POST /api/notifications/test`.
- Actuator: liveness/readiness/health, metrics; management port not exposed via nginx.
- **Secrets:** `.env` (gitignored) + `.env.example`; the realm config contains no secrets (env substitution).

### Frontend
- Latest Angular: standalone components, **signals**, zoneless, OnPush, lazy routes, Vitest.
- **Angular Material + CDK**, custom M3 theme, light/dark/system.
- **Meta title per page:** route `title` + a custom `TitleStrategy` (translated, suffix ` · Heimdall`).
- **Loading states:** route progress bar; skeleton loaders; every async view has loading/empty/error states.
- **Error UX:** `**` → custom 404; 500/unexpected-error page; offline banner (service worker serves the cached shell); a **session-expired dialog** on 401 ("Sign in again", keeps the return URL, no lost input).
- **Login redirect:** full-page navigation to `/oauth2/authorization/keycloak`; the return URL is stored in `sessionStorage` and restored after the callback.
- **PWA:** `@angular/pwa`; `ngsw-config` **excludes** `/api/**`, `/oauth2/**`, `/login/**`, `/logout`, `/auth/**` from navigation handling and data caching. `SwPush` handles subscriptions.
- **i18n:** Transloco `en` + `pt-PT`; `registerLocaleData` for both; locale saved on the profile. Check that Keycloak's Portuguese bundle covers pt-PT; if not, ship pt-PT messages in a custom Keycloak theme.
- Global `ErrorHandler` → Faro + friendly toast.
- **Faro:** collector at same-origin `/faro`; session in `sessionStorage`; traces propagate to `/api`; no PII.
- **Amplitude:** Browser SDK, `serverZone: 'EU'`, initialized only after consent; page views on `NavigationEnd`; a typed event catalogue; `userId` = internal UUID (never email or name). The Amplitude project must be **created in the EU region** (it can't be migrated later).
- **Consent banner:** after first login; choice saved on the profile and changeable in Settings. Declining = no Amplitude SDK loaded at all.
- **Settings page:** language, theme, analytics consent, profile (read from Keycloak + link to the Keycloak account console), push enable/disable + test.

### Security headers (nginx)
HSTS (prod), `X-Content-Type-Options`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`,
`frame-ancestors 'none'`, and a **CSP** allowing self + Amplitude EU endpoints. Angular inline styles are handled with
`ngCspNonce` or a `style-src` exception (builder picks and documents it).

### Observability & logging
- **Backend:** Micrometer Observation → OTLP (traces, metrics) and **logs over OTLP** (the same path on host and in containers). Console logs are human-readable in `dev` and structured JSON elsewhere. Every log line carries `traceId`/`spanId` and the internal user id. Never log tokens, emails, amounts or request bodies.
- **Keycloak:** OTLP tracing enabled; metrics via its Prometheus endpoint on the management port (9000), scraped by Alloy, with `KC_EVENT_METRICS_USER_ENABLED` for login-failure metrics.
- **Frontend:** Faro (errors, Web Vitals, browser → backend traces).
- **Dev:** single `grafana/otel-lgtm` container. **Prod:** separate components, decided with hosting.
- Dashboards (JVM, HTTP RED, DB pool, Keycloak, Web Vitals) and alerts (5xx rate, p95 latency, instance down, login failures spike) committed under `infra/`.

### Documentation
Javadoc on each module's public API; TSDoc on exported services and models; root README (run any mode in one command);
per-app READMEs; ADRs in `docs/adr/` (including Faro-as-necessary and hand-written TS types).

### Testing & quality gates (branch protection on `main`, merges blocked when red)
- **Backend:** JUnit 5; Testcontainers (Postgres, Keycloak); Modulith verify; ArchUnit; JaCoCo.
- **Frontend:** Vitest; Playwright E2E against the `full` profile (login → dashboard → settings language/theme → logout, 404, session-expired) with axe a11y checks; Angular bundle budgets.
- **SonarQube Cloud** quality gate: ≥80% coverage on new code, no new bugs or vulnerabilities, duplication limit.
- Lint/format: Spotless (Java), angular-eslint + Prettier; also in a pre-commit hook.
- Security: CodeQL, GitHub secret scanning + push protection, Dependabot, Trivy image scan.
- CI publishes images to GHCR on `main`.

## Left to the builder
Exact versions; Web Push Java library; Spotless formatter flavor; pre-commit tool; Keycloak theme styling (Keycloakify or
plain FTL); dashboard panel details; CSP nonce vs style exception.

## Verification (end of milestone)
1. `dev` mode boots; log in via Keycloak → dashboard; tab title changes per page.
2. Settings: switch to pt-PT + dark; both persist across logout/login, and the Keycloak login page is in pt-PT.
3. Register in dev → verification mail in Mailpit → login works. Prod realm config has registration off.
4. Unknown URL → 404 page. Expire the session → session-expired dialog → sign in returns to the same page. Stop the backend → error handling; go offline in `full` → offline banner with the shell still served.
5. `full` mode: enable push → test notification arrives on desktop. `tunnel` mode: install the PWA on Android + iOS → test notification arrives.
6. Decline consent → zero Amplitude requests (network tab); accept → events in the Amplitude EU project.
7. Grafana: one request's trace spans browser (Faro) → Spring → Postgres (nginx emits no spans); its logs link by traceId; dashboards populated; a forced 5xx burst fires the alert.
8. Logout → Keycloak SSO session gone (next login prompts for a password). "Sign out" the user's sessions from the Keycloak admin console → their open app session gets 401 on the next API call.
9. Re-running keycloak-config-cli against an existing realm applies changes (e.g. registration off) idempotently.
10. A PR runs all gates (tests, Sonar, CodeQL, Trivy, Playwright, lint) and `main` rejects a red merge.
