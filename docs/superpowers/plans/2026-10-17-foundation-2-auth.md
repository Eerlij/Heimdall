# Foundation 2/7: Keycloak + BFF Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Log in through Keycloak behind nginx on one origin, with Spring as the only OIDC client (BFF): the browser
holds only an HttpOnly session cookie and a CSRF token, `/api/**` answers 401 when signed out, logout ends both
sessions, and a Keycloak-side sign-out kills the app session even across backend restarts. No UI yet.

**Architecture:** Compose gains Keycloak (own DB, `/auth` relative path), a one-shot keycloak-config-cli that applies
the realm as code, Mailpit, and nginx as the single origin `http://localhost`. The `identity` module owns the security
config, an explicit Keycloak client registration (browser endpoints on the public URL, back-channel endpoints on the
internal URL), the `app_user` upsert at login, `GET /api/me` and `GET /api/csrf`. Sessions live in Postgres (Spring
Session JDBC); back-channel logout finds a user's sessions through Spring Session's principal-name index, so the
"OIDC session ↔ app session" link is persisted with the session itself and needs no extra table.

**Tech Stack:** Spring Boot 4.1.1 (Spring Security 7.1.1, Spring Session 4.1.1, Spring Framework 7.0.9), Keycloak 26.8
(`quay.io/keycloak/keycloak:26.8`), keycloak-config-cli `adorsys/keycloak-config-cli:6.5.1-26`, Mailpit
`axllent/mailpit:v1.31`, nginx `nginx:1.31-alpine`, PostgreSQL 18, Testcontainers, Nimbus JOSE (already on the
classpath via oauth2-client) for test-signed logout tokens.

**Spec:** `docs/superpowers/specs/2026-10-16-foundation-design.md` (Backend → Auth (BFF), Run modes, Persistence).
Plan 1 (`2026-10-16-foundation-1-walking-skeleton.md`) is merged; its Global Constraints still apply.

## Decisions taken while brainstorming Plan 2 (2026-10-17)
- **Validation is manual** for the browser flow (walkthrough in Task 5). Automated tests stay at backend level with
  Spring Security's mock login and a real Postgres; no Keycloak container in tests (Playwright covers it in plan 7).
- **Back-channel logout must survive backend restarts** (user's choice): the session map is persisted in Postgres.
  Implemented over Spring Session's `FindByIndexNameSessionRepository` (Claude's call, see ADR-0012).
- **Logout is a top-level form POST** (user's choice, keeps ADR-0004 "no token reachable from JavaScript"): the SPA
  gets a masked CSRF token from `GET /api/csrf`, submits a hidden `<form method=post action=/logout>` with `_csrf`, and
  the browser follows Spring's standard 302 to Keycloak's end-session endpoint (`id_token_hint` never touches JS).
- **`app_user` stores no PII:** `id` (internal UUID), `keycloak_subject`, `created_at`. Name/email come from the ID
  token in the session.
- **`ui_locales`:** `/oauth2/authorization/keycloak?ui_locales=<tag>` forwards an allowlisted tag
  (`en`→`en`, `pt-PT`→`pt`; Keycloak 26.8 ships `messages_pt`, no `pt_PT`); anything else is dropped.
- **No saved-request redirect:** the SPA keeps its return URL in `sessionStorage` (spec), so the server uses
  `NullRequestCache`; login success always lands on `/`, and anonymous requests never create session rows.
- **API errors are RFC 9457 everywhere**, including 401/403 from the security layer.
- **nginx in Plan 2 wires `dev` only.** The `full` profile keeps booting (backend container gets the new env) but
  browsing `full` through nginx, and its `BACKCHANNEL_LOGOUT_URL`, are plan 7.

## Global Constraints
- Everything from Plan 1's Global Constraints (Java 25, Boot 4.1.1, `./mvnw`, `timestamptz`/`Instant`, LF, public repo,
  no committed secrets, commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`).
- One origin per mode; dev origin **`http://localhost`** (`PUBLIC_ORIGIN`). Keycloak public URL
  **`${PUBLIC_ORIGIN}/auth`**, realm **`heimdall`**, client **`heimdall-bff`** (confidential, standard flow only,
  PKCE S256), registration id **`keycloak`**.
- Keycloak tokens are used at login only: no refresh, nothing relayed to the browser, no authorized-client store.
  API auth = Spring session only.
- `/api/**` unauthenticated → **401 problem+json**, never a redirect. Only navigation to
  `/oauth2/authorization/keycloak` logs in.
- CSRF: `csrf.spa()` (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header; masked `_csrf` parameter for form posts).
- Session cookie `SESSION`: `HttpOnly`, `SameSite=Lax`, `Secure` unless `HEIMDALL_COOKIE_SECURE=false` (plain-http
  localhost only); idle timeout `HEIMDALL_SESSION_TIMEOUT` default **30m**.
- `server.forward-headers-strategy=framework`; nginx is the only edge and overwrites every forwarded header.
- Self-registration: realm default **off**; `.env.example` (dev) turns it on.
- Secrets only in `infra/.env`: `KEYCLOAK_ADMIN_PASSWORD`, `HEIMDALL_KEYCLOAK_CLIENT_SECRET` (plus Plan 1's).
  Startup fails naming any missing one.
- Never log tokens, emails or names — including nginx access logs (no query strings).

## Review Focus
1. **Keycloak sign-out leaves the app session alive.** The internal re-post uses `Cookie: SESSION=<raw id>`, a
   `{baseScheme}://localhost{basePort}` URI and the handler/registry wiring; any slip fails silently. Task 4's
   end-to-end test posts a test-signed logout token and asserts the JDBC session row is gone.
2. **`/api/**` redirects to Keycloak** (CORS status-0 in Angular) or creates session rows. Task 2 tests GET, POST, an
   unknown `/api` path and an HTML-accepting GET all return 401 problem+json, no `Location`, no `SESSION` cookie.
3. **Two tabs log in at once for a new user** → two `app_user` rows or a 500. Task 3's upsert is a single
   `INSERT … ON CONFLICT … RETURNING`, tested twice for the same subject.
4. **Arbitrary `ui_locales` or spoofed forwarded headers reach Keycloak.** Task 2 tests the allowlist, and that
   `redirect_uri` follows nginx's `X-Forwarded-Proto/Host`.
5. **Logout without CSRF, as a GET, or with an expired app session.** Task 2 tests 403 without `_csrf`, that `GET
   /logout` doesn't end the session, and that an anonymous logout still sends the browser to Keycloak's end-session.

---

### Task 1: Keycloak, realm as code, Mailpit and nginx

**Files:**
- Modify: `infra/docker-compose.yml`, `infra/.env.example`
- Create: `infra/keycloak/realm/heimdall.yaml`, `infra/nginx/templates/default.conf.template`

**Interfaces:**
- Produces: origin `http://localhost` (nginx `127.0.0.1:80`); `/auth/` → `keycloak:8080`; `/api/`, `/oauth2/`,
  `/login/`, `/logout` → `${BACKEND_UPSTREAM}` (dev `host.docker.internal:8080`); Mailpit UI `127.0.0.1:8025`;
  issuer `http://localhost/auth/realms/heimdall`; back-channel URL in Keycloak `${BACKCHANNEL_LOGOUT_URL}`.

- [ ] **Step 1:** `.env.example` (dev values, a comment per group): `KEYCLOAK_ADMIN_PASSWORD`,
  `HEIMDALL_KEYCLOAK_CLIENT_SECRET` (`change-me-dev-only`), `PUBLIC_ORIGIN=http://localhost`,
  `KC_REGISTRATION_ALLOWED=true`, `HEIMDALL_COOKIE_SECURE=false`, `BACKEND_UPSTREAM=host.docker.internal:8080`,
  `BACKCHANNEL_LOGOUT_URL=http://host.docker.internal:8080/logout/connect/back-channel/keycloak`
  (comment: dev-only; plan 7 sets the `full`/`tunnel` value).
- [ ] **Step 2:** Scope `postgres` env like `backend` already is: replace `env_file: .env` with explicit
  `POSTGRES_PASSWORD`, `HEIMDALL_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD` (`${VAR:?…}`), so it never sees Keycloak/client
  secrets.
- [ ] **Step 3:** Service `keycloak` (`quay.io/keycloak/keycloak:26.8`, `command: start`, same flags in every mode):
  `KC_DB=postgres`, `KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak`, `KC_DB_USERNAME=keycloak`, `KC_DB_PASSWORD`,
  `KC_HTTP_ENABLED=true`, `KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=${PUBLIC_ORIGIN}/auth`,
  `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, `KC_PROXY_HEADERS=xforwarded`, `KC_HEALTH_ENABLED=true`,
  `KC_BOOTSTRAP_ADMIN_USERNAME=admin`, `KC_BOOTSTRAP_ADMIN_PASSWORD`; `extra_hosts: host.docker.internal:host-gateway`
  (Linux/CI parity); `depends_on postgres: service_healthy`; no published ports. Healthcheck without curl (absent from
  the image): bash `/dev/tcp` GET `127.0.0.1:9000/auth/health/ready` (management inherits the relative path), grep
  `"UP"`; `start_period: 60s`.
- [ ] **Step 4:** Service `keycloak-config` (`adorsys/keycloak-config-cli:6.5.1-26`, `restart: "no"`):
  `KEYCLOAK_URL=http://keycloak:8080/auth`, `KEYCLOAK_USER=admin`, `KEYCLOAK_PASSWORD`,
  `IMPORT_FILES_LOCATIONS=/config/*`, `IMPORT_VARSUBSTITUTION_ENABLED=true`, plus the variables the realm file uses;
  mount `./keycloak/realm:/config:ro`; `depends_on keycloak: service_healthy`. Compatibility of 6.5.1-26 with Keycloak
  26.8 is not upstream-verified: Step 8's exit 0 is the gate; on failure use the newest `<cli>-26.x` tag that passes.
- [ ] **Step 5:** `heimdall.yaml`. Substitution is textual and runs before YAML parsing, so **every substituted value is
  double-quoted** (`"$(env:VAR)"`); no literal secrets. Realm `heimdall`, `enabled: true`,
  `registrationAllowed: "$(env:KC_REGISTRATION_ALLOWED:-false)"`, `verifyEmail`, `resetPasswordAllowed`,
  `loginWithEmailAllowed`, `duplicateEmailsAllowed: false`, `bruteForceProtected`, `sslRequired: external`,
  `passwordPolicy: "length(12) and notUsername and notEmail"`, `internationalizationEnabled`,
  `supportedLocales: [en, pt]`, `defaultLocale: en`,
  `smtpServer: {host: mailpit, port: "1025", from: no-reply@heimdall.localhost, fromDisplayName: Heimdall}` (prod SMTP
  is a non-goal; becomes env-driven with hosting).
  Client `heimdall-bff`: `publicClient: false`, `secret: "$(env:HEIMDALL_KEYCLOAK_CLIENT_SECRET)"`,
  `standardFlowEnabled: true`, `directAccessGrantsEnabled/implicitFlowEnabled/serviceAccountsEnabled: false`,
  `frontchannelLogout: false`, `redirectUris: ["$(env:PUBLIC_ORIGIN)/login/oauth2/code/keycloak"]`, `webOrigins: []`,
  attributes `pkce.code.challenge.method: S256`, `post.logout.redirect.uris: "$(env:PUBLIC_ORIGIN)/"`,
  `backchannel.logout.url: "$(env:BACKCHANNEL_LOGOUT_URL)"`, `backchannel.logout.session.required: "true"`,
  `backchannel.logout.revoke.offline.tokens: "false"`. Default client scopes include `profile`, `email`.
- [ ] **Step 6:** Service `mailpit` (`axllent/mailpit:v1.31`): UI `127.0.0.1:8025:8025`, SMTP 1025 internal only.
- [ ] **Step 7:** Service `nginx` (`nginx:1.31-alpine`, `127.0.0.1:80:80`, `extra_hosts` as Keycloak,
  `NGINX_ENVSUBST_FILTER=^BACKEND_UPSTREAM$` so nginx's own `$host` etc. survive envsubst, mount
  `./nginx/templates:/etc/nginx/templates:ro`, `depends_on: {keycloak: service_healthy, keycloak-config:
  service_completed_successfully}` — this is also what lets `up --wait` finish with a one-shot container).
  Template, one `server`:
  - `log_format noquery '$remote_addr [$time_local] "$request_method $uri $server_protocol" $status $body_bytes_sent $request_time'`
    and `access_log … noquery` (comment: query strings carry `code`, `state`, `id_token_hint`).
  - `resolver 127.0.0.11 valid=10s` with upstreams in variables (`set $keycloak http://keycloak:8080;`) so a recreated
    container's new IP is picked up.
  - Shared proxy headers: `Host $host`, `X-Forwarded-Proto $scheme`, `X-Forwarded-Host $host`,
    `X-Forwarded-Port $server_port`, `X-Forwarded-For $remote_addr` (edge: never trust a client-sent chain),
    `Forwarded ""`, `X-Forwarded-Prefix ""` (Spring honours both if passed through);
    `proxy_buffer_size 128k`, `proxy_buffers 4 256k` (Keycloak's auth cookies and Spring's 302 to end-session carry
    large headers).
  - `location /auth/` → `$keycloak`; `location ~ ^/(api|oauth2|login|logout)(/|$)` → `http://${BACKEND_UPSTREAM}`
    (also covers `/login/oauth2/code/*` and `/logout/connect/back-channel/*`); `location /` → `200 text/plain
    "Heimdall: the app shell arrives in plan 3"` (replaced in plan 3).
  - Comment: prod must not expose `/auth/admin` publicly (hosting plan).
- [ ] **Step 8: Verify the stack and the realm**
  Run: `docker compose -f infra/docker-compose.yml up -d --wait` then
  `docker compose -f infra/docker-compose.yml ps -a keycloak-config --format '{{.State}} {{.ExitCode}}'` and
  `curl -s http://localhost/auth/realms/heimdall/.well-known/openid-configuration`
  Expected: `up` returns; `exited 0`; `"issuer":"http://localhost/auth/realms/heimdall"`, `authorization_endpoint` on
  `http://localhost/auth/…`. `docker compose logs nginx` shows no query strings.
- [ ] **Step 9: Verify idempotent re-apply (spec verification 9)**
  Run: set `KC_REGISTRATION_ALLOWED=false` in `infra/.env`, `docker compose -f infra/docker-compose.yml run --rm
  keycloak-config; echo $?` → `0`; `http://localhost/auth/realms/heimdall/account` login page has no "Register" link.
  Restore `true`, re-run, link is back.
- [ ] **Step 10: Commit** `feat(infra): add keycloak with realm as code, mailpit and nginx origin`

---

### Task 2: BFF security: client registration, sessions, 401s, CSRF, logout

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `HeimdallApplication.java`
  (`REQUIRED_PROPERTIES`), `shared/web/GlobalExceptionHandler.java`, `infra/docker-compose.yml` (backend env);
  tests `TestcontainersConfiguration.java` (class and bean method `public`, used from other packages),
  `shared/web/GlobalExceptionHandlerTests.java`, `RequiredPropertiesTests.java`
- Create: `backend/src/main/resources/db/migration/V2__spring_session.sql`, `backend/src/test/resources/application.properties`,
  `identity/internal/KeycloakProperties.java`, `identity/internal/KeycloakClientRegistration.java`,
  `identity/internal/UiLocalesAuthorizationRequestResolver.java`, `identity/internal/SessionConfig.java`,
  `identity/internal/ProblemDetailsSecurityHandler.java`, `identity/internal/SecurityConfig.java`,
  `identity/internal/CsrfController.java`
- Test: `.../identity/internal/SecurityConfigTests.java`, `.../identity/internal/UiLocalesAuthorizationRequestResolverTests.java`,
  `.../identity/internal/ManagementEndpointsTests.java`, `.../identity/internal/TestSessions.java` (helper)

**Interfaces:**
- Consumes: Plan 1's `GlobalExceptionHandler`, `TestcontainersConfiguration`.
- Produces: `@ConfigurationProperties("heimdall.keycloak") @Validated record KeycloakProperties(@NotNull URI publicUrl,
  @NotNull URI internalUrl, @NotBlank String realm, @NotBlank String clientId, @NotBlank String clientSecret)`
  with `URI publicEndpoint(String path)` / `URI internalEndpoint(String path)` (`<url>/realms/<realm>/protocol/openid-connect/<path>`);
  bean `ClientRegistrationRepository` with registration `KeycloakClientRegistration.REGISTRATION_ID = "keycloak"`;
  `SecurityFilterChain`; `GET /api/csrf` → `record CsrfResponse(String parameterName, String token)` (masked);
  test helper `TestSessions.save(FindByIndexNameSessionRepository<?>, OidcUser): String sessionId` storing a
  `SecurityContext` with an `OAuth2AuthenticationToken` (registration `keycloak`) — reused by Tasks 3–4.

- [ ] **Step 1:** pom: add `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-session-jdbc`; tests
  `spring-boot-starter-security-oauth2-client-test`, `spring-boot-starter-session-jdbc-test`.
- [ ] **Step 2:** `V2__spring_session.sql`: verbatim copy of Spring Session 4.1.1
  `org/springframework/session/jdbc/schema-postgresql.sql`, header comment naming source/version like V1.
- [ ] **Step 3:** `application.yml`: `server.forward-headers-strategy: framework`;
  `server.servlet.session.timeout: ${HEIMDALL_SESSION_TIMEOUT:30m}`, `cookie.http-only: true`,
  `cookie.same-site: lax`, `cookie.secure: ${HEIMDALL_COOKIE_SECURE:true}`;
  `spring.session.jdbc.initialize-schema: never` (Flyway owns the schema);
  `heimdall.public-origin: ${PUBLIC_ORIGIN:http://localhost}` (the browser origin; used where no request is at hand),
  `heimdall.keycloak.public-url: ${KEYCLOAK_PUBLIC_URL:${heimdall.public-origin}/auth}`,
  `internal-url: ${KEYCLOAK_INTERNAL_URL:${heimdall.keycloak.public-url}}` (dev: Spring on the host reaches Keycloak
  through nginx), `realm: heimdall`, `client-id: heimdall-bff`, `client-secret: ${HEIMDALL_KEYCLOAK_CLIENT_SECRET}`
  (no default). Add `HEIMDALL_KEYCLOAK_CLIENT_SECRET` to `REQUIRED_PROPERTIES`.
  `src/test/resources/application.properties` (sits beside main's `application.yml`, doesn't shadow it):
  `heimdall.keycloak.client-secret=test-only`. `RequiredPropertiesTests`: `assumeTrue` both env vars unset; message
  names both.
  Compose `backend` (full profile): `HEIMDALL_KEYCLOAK_CLIENT_SECRET`, `PUBLIC_ORIGIN`,
  `KEYCLOAK_INTERNAL_URL=http://keycloak:8080/auth`, `HEIMDALL_COOKIE_SECURE=false`.
- [ ] **Step 4: Write the failing tests.** `SecurityConfigTests`: `@SpringBootTest @AutoConfigureMockMvc
  @Import(TestcontainersConfiguration.class)`; mock principal via
  `oidcLogin().clientRegistration(repo.findByRegistrationId("keycloak"))`; "real session" = `TestSessions.save(...)` +
  `cookie(new Cookie("SESSION", id))`.
  ```java
  @Test void apiGetWithoutSessionIs401Problem()        // GET /api/me → 401, application/problem+json, $.status 401, no Location
  @Test void apiPostWithoutSessionIs401Problem()       // POST /api/anything with csrf() → same
  @Test void unknownApiPathWithoutSessionIs401()       // GET /api/does-not-exist → 401
  @Test void htmlGetToApiCreatesNoSession()            // GET /api/me, Accept: text/html → 401, no Set-Cookie SESSION
  @Test void browserNavigationRedirectsToLogin()       // GET /somewhere, Accept: text/html → 302 …/oauth2/authorization/keycloak
  @Test void nonHtmlNonApiRequestFallsBackTo401()      // GET /somewhere, no Accept → 401 (first entry point is the default)
  @Test void loginRedirectsToPublicKeycloakWithPkce()  // GET /oauth2/authorization/keycloak → 302 to
      // http://localhost/auth/realms/heimdall/protocol/openid-connect/auth, client_id=heimdall-bff,
      // code_challenge_method=S256, scope contains openid, redirect_uri=http://localhost/login/oauth2/code/keycloak
  @Test void redirectUriFollowsForwardedHeaders()      // same with X-Forwarded-Proto: https, X-Forwarded-Host: example.test
      // → redirect_uri=https://example.test/login/oauth2/code/keycloak
  @Test void sessionCookieFlags()                      // Set-Cookie SESSION from the login redirect has HttpOnly, Secure, SameSite=Lax
  @Test void responsesCarryXsrfCookie()                // any GET → Set-Cookie XSRF-TOKEN, not HttpOnly
  @Test void generatedLoginAndLogoutPagesAreGone()     // authenticated GET /login and GET /logout → 404; session row still exists
  @Test void csrfEndpointReturnsMaskedToken()          // GET /api/csrf (real session) → {"parameterName":"_csrf","token":…}
  @Test void logoutWithoutCsrfIs403Problem()           // POST /logout (real session) without _csrf → 403 problem+json; row still exists
  @Test void formLogoutRedirectsToKeycloakEndSession() // POST /logout, form param _csrf = token from /api/csrf, same SESSION
      // and the XSRF-TOKEN cookie that response set (the cookie repository holds the unmasked token)
      // → 302, Location starts with http://localhost/auth/realms/heimdall/protocol/openid-connect/logout,
      // has id_token_hint and post_logout_redirect_uri=http://localhost/ (URL-encoded); session row deleted
  @Test void logoutWithExpiredSessionStillEndsKeycloakSession() // POST /logout with csrf(), no session → 302 to
      // …/openid-connect/logout?client_id=heimdall-bff&post_logout_redirect_uri=… (Keycloak asks to confirm)
  @Test void accessDeniedFromControllerIs403Problem()  // test-only controller /api/test/denied throws AccessDeniedException,
      // with oidcLogin() → 403 problem+json (not the catch-all 500)
  ```
  `ManagementEndpointsTests` (`RANDOM_PORT`, `management.server.port=0`, `@LocalManagementPort`, JDK `HttpClient`):
  `healthIsPublic` → `/actuator/health` 200 without a session; `metricsNeedAuth` → `/actuator/metrics` 401.
  `UiLocalesAuthorizationRequestResolverTests` (unit, real registration): `pt-PT` → `ui_locales=pt`; `en` → `en`;
  `fr`, `pt-PT\r\nX: y`, two `ui_locales` params, absent → no `ui_locales`.
- [ ] **Step 5: Run to verify failure** `./mvnw -B -ntp test -Dtest='SecurityConfigTests,ManagementEndpointsTests,UiLocales*'` → compile errors.
- [ ] **Step 6:** `KeycloakClientRegistration` (`@Configuration`, `@EnableConfigurationProperties(KeycloakProperties.class)`):
  `@Bean ClientRegistrationRepository` → `InMemoryClientRegistrationRepository` with one registration:
  `AUTHORIZATION_CODE`, `CLIENT_SECRET_BASIC`, `redirectUri "{baseUrl}/login/oauth2/code/{registrationId}"`, scopes
  `openid, profile, email`, `userNameAttributeName "sub"`; `authorizationUri` + `issuerUri` on the **public** URL,
  `tokenUri` + `jwkSetUri` on the **internal** URL, **no** `userInfoUri` (the ID token carries the claims; one call fewer
  per login), `providerConfigurationMetadata(Map.of("end_session_endpoint", public …/logout))`. PKCE is Spring
  Security 7's default (`requireProofKey=true`); no setting, Keycloak enforces S256. Javadoc: why manual (the issuer is
  the public URL, unreachable from containers, so discovery can't be used).
  Also `@Bean OAuth2AuthorizedClientRepository` that loads nothing and saves nothing (Javadoc: tokens are used at login
  only; Boot's default would keep every user's access/refresh token in memory forever).
- [ ] **Step 7:** `UiLocalesAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver`: wraps
  `DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization")`; reads `getParameterValues("ui_locales")`,
  accepts exactly one value mapped via `Map.of("en", "en", "pt-PT", "pt")`, else leaves the request untouched; adds via
  `OAuth2AuthorizationRequest.from(r).additionalParameters(params -> params.put("ui_locales", v))`.
- [ ] **Step 8:** `SessionConfig`: `@Bean PostgreSqlJdbcIndexedSessionRepositoryCustomizer` (Boot doesn't register it;
  without it parallel SPA requests on one session hit duplicate-key errors on attribute writes) and
  `@Bean DefaultCookieSerializerCustomizer` → `setUseBase64Encoding(false)` (back-channel logout replays the raw session
  id as the cookie; Review Focus 1).
- [ ] **Step 9:** `ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler`: writes
  `ProblemDetail.forStatus(401|403)` as `application/problem+json` via the context's JSON `HttpMessageConverter`
  (no message/exception text in the body).
- [ ] **Step 10:** `SecurityConfig` (`@Configuration`; Boot already enables web security), one `SecurityFilterChain`:
  - `authorizeHttpRequests`: `EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)` permitAll (the chain also
    guards the management port), `/error` permitAll, `anyRequest().authenticated()`;
  - `exceptionHandling`: `defaultAuthenticationEntryPointFor(problemHandler, pathPattern("/api/**"))` registered first
    (so it is also the fallback), `accessDeniedHandler(problemHandler)`;
  - `requestCache(c -> c.requestCache(new NullRequestCache()))`;
  - `oauth2Login`: `loginPage("/oauth2/authorization/keycloak")` (disables Spring's generated login/logout pages),
    `failureUrl("/?login_error")`, `authorizationEndpoint.authorizationRequestResolver(resolver)`,
    `authorizedClientRepository(noOpRepository)`;
  - `csrf(CsrfConfigurer::spa)`;
  - `logout(logoutSuccessHandler(handler))`: `new OidcClientInitiatedLogoutSuccessHandler(repo)`,
    `setPostLogoutRedirectUri("{baseUrl}/")`, `setDefaultTargetUrl(<public end_session>?client_id=heimdall-bff&post_logout_redirect_uri=<public origin>/)`
    with the origin from `heimdall.public-origin` — comment: used when the app session already expired, so the SSO
    session still ends (Keycloak asks to confirm).
  No `oidcLogout` yet (Task 4).
- [ ] **Step 11:** `CsrfController` `GET /api/csrf` (`CsrfToken token` argument → `new CsrfResponse(token.getParameterName(), token.getToken())`).
  Javadoc: the masked token for top-level form posts (logout); XHRs use the `XSRF-TOKEN` cookie instead.
- [ ] **Step 12:** `GlobalExceptionHandler`: add
  `@ExceptionHandler({AccessDeniedException.class, AuthenticationException.class}) void rethrowSecurity(RuntimeException ex) { throw ex; }`
  with Javadoc: rethrowing the same instance lets `ExceptionTranslationFilter` answer 401/403 instead of the catch-all
  500 (Framework 7 propagates it without a warning). `GlobalExceptionHandlerTests` (about error bodies, not security)
  gets `@AutoConfigureMockMvc(addFilters = false)`.
- [ ] **Step 13: Run to verify pass** `./mvnw -B -ntp verify` → `BUILD SUCCESS` (Modulith verify included).
- [ ] **Step 14: Commit** `feat(identity): add keycloak BFF login, problem 401/403s, SPA CSRF and form logout`

---

### Task 3: Application users and `GET /api/me`

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__app_user.sql`, `identity/AuthenticatedUser.java` (module API),
  `identity/internal/AppUserRepository.java`, `identity/internal/AppUserOidcUserService.java`,
  `identity/internal/MeController.java`
- Modify: `identity/internal/SecurityConfig.java` (`userInfoEndpoint.oidcUserService`)
- Test: `.../identity/internal/AppUserRepositoryTests.java`, `.../identity/internal/AppUserOidcUserServiceTests.java`,
  `.../identity/internal/MeControllerTests.java`

**Interfaces:**
- Produces: `public final class AuthenticatedUser extends DefaultOidcUser` with `UUID userId()` (Javadoc: the internal
  id every owned row references; other modules use this, never `sub`), fixed `serialVersionUID`;
  `AppUserRepository.upsert(UUID keycloakSubject): UUID`; `GET /api/me` → `record MeResponse(UUID id, String name, String email)`.

- [ ] **Step 1:** `V3__app_user.sql`: `app_user(id uuid primary key default gen_random_uuid(),
  keycloak_subject uuid not null unique, created_at timestamptz not null default now())`; comment: no PII by design.
- [ ] **Step 2: Write the failing tests**
  ```java
  // AppUserRepositoryTests (@JdbcTest @Import({TestcontainersConfiguration.class, AppUserRepository.class}))
  @Test void upsertCreatesThenReturnsSameId()      // upsert(s) twice → equal ids, one row for s
  @Test void differentSubjectsGetDifferentIds()
  // AppUserOidcUserServiceTests (unit; delegate stubbed to return a DefaultOidcUser with sub = random UUID)
  @Test void returnsAuthenticatedUserWithUpsertedId() // instanceof AuthenticatedUser, userId == repository result,
      // getName() == sub, authorities and id token preserved
  @Test void nonUuidSubjectFailsLogin()            // sub "not-a-uuid" → OAuth2AuthenticationException("invalid_subject")
  // MeControllerTests (@SpringBootTest @AutoConfigureMockMvc, oidcLogin().oidcUser(new AuthenticatedUser(...)))
  @Test void meReturnsIdNameEmail()                // 200 {"id": <uuid>, "name": "Ada Lovelace", "email": "ada@example.com"}
  ```
- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4:** `AppUserRepository` (`@Repository`, `JdbcClient`): one statement
  `insert into app_user (keycloak_subject) values (:sub) on conflict (keycloak_subject) do update set keycloak_subject = excluded.keycloak_subject returning id`
  (the no-op update makes `RETURNING` yield the existing row; race-safe for concurrent first logins).
- [ ] **Step 5:** `AppUserOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser>`: decorates
  `OidcUserService` (keeps its claim validation and authority mapping), parses `sub` as UUID (failure →
  `OAuth2AuthenticationException` code `invalid_subject` → `failureUrl`), upserts, returns
  `new AuthenticatedUser(userId, authorities, idToken, userInfo, "sub")`.
- [ ] **Step 6:** `MeController` `GET /api/me`, `@AuthenticationPrincipal AuthenticatedUser user` →
  `new MeResponse(user.userId(), user.getFullName(), user.getEmail())`. Javadoc on the endpoint.
- [ ] **Step 7:** Wire `oidcUserService` in `SecurityConfig`. `TestSessions.save` callers may now pass an `AuthenticatedUser`.
- [ ] **Step 8: Run to verify pass** `./mvnw -B -ntp verify` → `BUILD SUCCESS`.
- [ ] **Step 9: Commit** `feat(identity): upsert app users at login and expose GET /api/me`

---

### Task 4: Back-channel logout backed by the session store

**Files:**
- Create: `identity/internal/SessionStoreOidcSessionRegistry.java`
- Modify: `identity/internal/SecurityConfig.java`
- Test: `.../identity/internal/SessionStoreOidcSessionRegistryTests.java`, `.../identity/internal/BackChannelLogoutTests.java`,
  `.../identity/internal/TestLogoutTokens.java` (helper)

**Interfaces:**
- Consumes: principals stored under `HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY`; principal name
  = `sub` (Task 2), indexed by Spring Session as `PRINCIPAL_NAME`; `TestSessions.save`.
- Produces: bean `OidcSessionRegistry`; bean `OidcBackChannelLogoutHandler`; endpoint
  `POST /logout/connect/back-channel/keycloak` (Spring Security). Test helper `TestLogoutTokens`: an RSA `JWK`, a JDK
  `HttpServer` serving its JWK set on a random port, and `sign(sub, sid, iss, aud): String` producing a logout token
  (`typ: logout+jwt`, `events: {"http://schemas.openid.net/event/backchannel-logout": {}}`, `jti`, `iat`).

- [ ] **Step 1: Write the failing tests**
  ```java
  // SessionStoreOidcSessionRegistryTests (@SpringBootTest + Testcontainers; sessions via TestSessions.save with
  // oidcUser(sub, sid, iss=http://localhost/auth/realms/heimdall, aud=heimdall-bff))
  @Test void logoutTokenWithSidMatchesOnlyThatSession()        // two sessions same sub, token sid=s1 → [session1]
  @Test void logoutTokenWithoutSidMatchesAllSessionsOfSubject() // → both, not a third user's
  @Test void wrongIssuerOrAudienceMatchesNothing()
  @Test void tokenWithoutSubjectMatchesNothing()                // sid-only → empty, WARN without ids (Keycloak always sends sub)
  @Test void unreadableSessionIsDeletedNotFatal()               // corrupt SPRING_SECURITY_CONTEXT bytes for one of the user's
      // sessions (JdbcClient update) → the other session is returned, the corrupt one is deleted, no exception
  @Test void newRegistryInstanceFindsExistingSessions()         // stateless: survives a restart by construction
  @Test void returnedInfoCarriesSessionIdAndNoCredentials()     // getSessionId() == stored id, getAuthorities() empty
  // BackChannelLogoutTests (RANDOM_PORT; @TestConfiguration overrides ClientRegistrationRepository with the same
  // registration but jwkSetUri = TestLogoutTokens' server)
  @Test void signedLogoutTokenDeletesThatSessionOnly()   // two sessions (sid s1, s2) → POST logout_token(sid=s1) to
      // http://localhost:{port}/logout/connect/back-channel/keycloak → 200; session1 row gone, session2 still there
  @Test void subjectOnlyTokenDeletesAllUserSessions()
  @Test void garbageTokenIs400WithoutSessionOrCsrf()     // logout_token=garbage → 400 OIDC error body, not 401/403
  @Test void tokenForWrongAudienceDeletesNothing()       // aud=other-client → 400; rows intact
  ```
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3:** `SessionStoreOidcSessionRegistry implements OidcSessionRegistry`, constructed with
  `FindByIndexNameSessionRepository<? extends Session>`:
  - `saveSessionInformation` → no-op; `removeSessionInformation(String)` → `null` (Javadoc on both: the session row *is*
    the link; Spring Session creates it, keeps it across id changes and deletes it on logout/expiry, so nothing goes stale;
    upstream callers null-check).
  - `removeSessionInformation(OidcLogoutToken t)`: `sub == null` → empty (stricter than upstream: lookup is by the
    principal index); else `findByPrincipalName(sub)`, per session read the security context **inside a try/catch for
    `SerializationFailedException`/`ClassCastException`** — on failure `deleteById` (same `sub`: fail closed) and WARN
    without ids; keep sessions whose principal is an `OidcUser` with the same issuer, an overlapping audience and, when
    `t.getSessionId() != null`, the same `sid` (same predicates as Spring's `InMemoryOidcSessionRegistry`); map to
    `new OidcSessionInformation(session.getId(), Map.of(), user)`. It doesn't delete matched sessions: the handler's
    internal request invalidates each through the normal logout handlers.
- [ ] **Step 4:** `SecurityConfig`: `@Bean OidcSessionRegistry` (above); `@Bean OidcBackChannelLogoutHandler` with
  `setSessionCookieName("SESSION")` and
  `setLogoutUri("{baseScheme}://localhost{basePort}/logout/connect/back-channel/{registrationId}")` (comment: Spring
  re-posts to itself; the host Keycloak used, e.g. `host.docker.internal`, may not resolve from inside); chain adds
  `.oidcLogout(o -> o.backChannel(Customizer.withDefaults()))` (picks up the handler bean, documented upstream).
  Note in the Javadoc (upstream limits): the handler's `RestTemplate` has no timeouts, and each logout re-fetches the
  JWK set once per session.
- [ ] **Step 5: Run to verify pass** `./mvnw -B -ntp verify` → `BUILD SUCCESS`.
- [ ] **Step 6: Commit** `feat(identity): persist back-channel logout links through the session store`

---

### Task 5: Docs and manual end-to-end check

**Files:**
- Create: `docs/adr/0012-back-channel-logout-via-session-index.md`
- Modify: `README.md`

- [ ] **Step 1:** ADR-0012 (Accepted, 2026-10-17). Context: the default in-memory registry loses links on restart while
  JDBC sessions survive. Decision: resolve sessions through Spring Session's principal index; no extra table.
  Consequences: needs logout tokens with `sub` (Keycloak 26.8 always sends it; `sid`-only tokens are ignored, stricter
  than upstream); principal name must stay `sub`; session cookies are no longer base64-encoded; a Keycloak sign-out
  while the backend is down is lost (Keycloak sends once) and that session lives until its idle timeout; changing a
  class stored in the session (e.g. `AuthenticatedUser`) means purging `spring_session`; a Redis move keeps this only
  with the indexed repository (`spring.session.redis.repository-type=indexed`).
- [ ] **Step 2:** README "Dev mode": `cp infra/.env.example infra/.env`, `docker compose -f infra/docker-compose.yml up -d --wait`,
  `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, browse `http://localhost`; Mailpit
  `http://localhost:8025`; Keycloak admin `http://localhost/auth/admin` (`admin`, password from `.env`); new `.env`
  keys; no volume reset needed (the `keycloak` DB exists since plan 1); health stays on `127.0.0.1:8081`.
- [ ] **Step 3: Manual walkthrough** (record results in the PR description):
  1. `http://localhost/api/me` → 401 problem, no redirect, no `SESSION` cookie.
  2. `http://localhost/oauth2/authorization/keycloak?ui_locales=pt-PT` → Keycloak login in Portuguese → Register →
     verification mail in Mailpit → verify → lands on `/` → `/api/me` shows id, name, email. `app_user` row has no email.
     Note whether Keycloak's `pt` wording reads as pt-PT (input for plan 3's i18n/theme work).
  3. DevTools console on `/`:
     `fetch('/api/csrf').then(r=>r.json()).then(c=>{const f=document.createElement('form');f.method='post';f.action='/logout';const i=document.createElement('input');i.type='hidden';i.name=c.parameterName;i.value=c.token;f.append(i);document.body.append(f);f.submit()})`
     → browser goes through Keycloak and back to `/`; `/api/me` → 401; logging in again asks for the password.
  4. Log in, restart the backend, `/api/me` still 200; Keycloak admin → Users → user → Sessions → Sign out →
     `/api/me` → 401 (back-channel across a restart).
  5. Cookies: `SESSION` HttpOnly + SameSite=Lax; `XSRF-TOKEN` readable; nothing token-like in browser storage;
     `docker compose logs nginx` has no query strings.
- [ ] **Step 4: Commit** `docs: record back-channel logout decision and dev auth walkthrough`

---

## Done when
- `docker compose up -d --wait` returns with Postgres, Keycloak, Mailpit and nginx healthy and `keycloak-config` exited 0.
- `./mvnw verify` is green, including every Review Focus test and the end-to-end back-channel test.
- The manual walkthrough passes.
- `--profile full` backend container reaches `UP` (`curl 127.0.0.1:8081/actuator/health`, unauthenticated).
