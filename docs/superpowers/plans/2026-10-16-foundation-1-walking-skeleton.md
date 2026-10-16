# Foundation 1/7: Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot 4 modular monolith that boots against containerized PostgreSQL, returns RFC 9457 errors, enforces module boundaries and formatting, builds as a container image, and is verified by GitHub Actions. There is no auth or UI yet.

**Architecture:** Monorepo with `backend/` (Maven, Spring Boot) and `infra/` (Docker Compose). Postgres hosts two databases (`heimdall`, `keycloak`) with separate roles. The backend is split into Spring Modulith modules (`identity`, `settings`, `notifications`, `shared`), currently empty except `shared`.

**Tech Stack:** Java 25 (Temurin), Spring Boot 4.1.1, Spring Modulith 2.1.1 (managed by Initializr), Maven Wrapper, PostgreSQL 18, Flyway, Testcontainers, ArchUnit 1.5.1, Spotless 3.10.4 (palantir-java-format), JaCoCo 0.8.15, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-16-foundation-design.md`

**Series:** 1 Walking skeleton (this) → 2 Keycloak + BFF auth → 3 Angular shell → 4 Observability → 5 PWA + push → 6 Consent, Amplitude, security headers → 7 Full/tunnel modes, E2E, quality gates.

## Global Constraints
- Java **25**; Spring Boot **4.1.1**; Maven via the committed wrapper (`./mvnw`). No global Maven install is assumed.
- Root package / groupId **`io.github.eerlij.heimdall`** (derived from the GitHub owner; change before Task 3 if a custom domain is preferred).
- Postgres image **`postgres:18`** in both compose and Testcontainers. In PG18 the data volume mounts at **`/var/lib/postgresql`**, not `/var/lib/postgresql/data`.
- App DB `heimdall` / role `heimdall`; Keycloak DB `keycloak` / role `keycloak`. Passwords only from `infra/.env` (gitignored). `infra/.env.example` holds dev-only placeholder values.
- **Public repo:** no real secret is ever committed.
- `spring.jpa.open-in-view=false`; `spring.threads.virtual.enabled=true`; `server.shutdown=graceful`; `spring.mvc.problemdetails.enabled=true`.
- Management (Actuator) on port **8081**, separate from the app port **8080**.
- All timestamps are `timestamptz`; Java types `Instant`/`OffsetDateTime`, never `LocalDateTime`/`java.util.Date` (enforced by ArchUnit).
- LF line endings in the repo (`.gitattributes`); the dev machine has `core.autocrlf=true`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus
1. **Container recreated → data gone.** A wrong PG18 volume path silently loses dev data. Task 2 verifies that data survives `docker compose down && up`.
2. **500 leaks internals.** Unhandled exceptions must not expose class names, messages or stack traces. Task 4 asserts the response body contains neither the exception message nor `Exception`.
3. **CRLF in shell scripts / `mvnw`** breaks the Linux containers and CI. Task 1 verifies `git ls-files --eol` shows `i/lf` for `*.sh` and `mvnw`.
4. **Changed `.env` passwords not applied.** The Postgres init script only runs on an empty volume. The README (Task 2) documents `docker compose down -v` to re-init; nothing is silent.
5. **Missing `infra/.env`.** The backend must fail fast with an error naming the missing property rather than connecting with a default. Task 3 has no password default, verified manually.

---

### Task 1: Repository conventions

**Files:**
- Create: `.gitattributes`, `.editorconfig`, `.gitignore`, `docs/adr/0000-template.md`, `docs/adr/0001-record-architecture-decisions.md`
- Modify: `README.md`

**Interfaces:**
- Produces: ADR format (`docs/adr/NNNN-kebab-title.md`, sections *Status, Context, Decision, Consequences*) used by Task 5 and all later plans.

- [ ] **Step 1:** Create `.gitattributes`: `* text=auto eol=lf`; `*.cmd text eol=crlf`; `*.bat text eol=crlf`; binary: `*.png *.jpg *.ico *.woff2 *.jar binary`.
- [ ] **Step 2:** Create `.editorconfig`: root; utf-8; LF; final newline; trim trailing whitespace (except `*.md`); indent 2 spaces default, 4 for `*.java`.
- [ ] **Step 3:** Create `.gitignore` covering: `infra/.env`, `**/target/`, `**/node_modules/`, `**/dist/`, `.idea/`, `*.iml`, `.vscode/` (except `extensions.json`), `.DS_Store`, `*.log`. Do **not** ignore `.claude/settings.json` (team plugin config) but ignore `.claude/settings.local.json`.
- [ ] **Step 4:** Renormalize existing files: `git add --renormalize .`
- [ ] **Step 5:** Write ADR template + ADR-0001 (decision: record architecture decisions as ADRs in `docs/adr/`, MADR-lite format, immutable once accepted, superseded by new ADRs).
- [ ] **Step 6:** Rewrite `README.md`: tagline, one-paragraph description, links to spec, plans, ADRs; "Getting started" placeholder filled by Task 2.
- [ ] **Step 7: Verify**
  Run: `git add -A && git ls-files --eol | grep -v 'i/lf'`
  Expected: no output apart from binary files (`-text`) and `*.cmd`.
- [ ] **Step 8: Commit** `chore: add repo conventions and ADR process`

---

### Task 2: Postgres in Docker Compose

**Files:**
- Create: `infra/docker-compose.yml`, `infra/.env.example`, `infra/postgres/init/01-create-databases.sh`
- Modify: `README.md` (Getting started: copy `.env.example` → `.env`, `docker compose up -d`, re-init note)

**Interfaces:**
- Produces: env vars `POSTGRES_PASSWORD`, `HEIMDALL_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD`; service `postgres` on `127.0.0.1:5432`; DBs `heimdall`, `keycloak`.

- [ ] **Step 1:** `infra/docker-compose.yml`: `name: heimdall`; service `postgres` (`image: postgres:18`, `env_file: .env`, port `127.0.0.1:5432:5432`, volume `pgdata:/var/lib/postgresql`, init dir mounted read-only at `/docker-entrypoint-initdb.d`, healthcheck `pg_isready -U postgres` interval 5s retries 10); named volume `pgdata`.
- [ ] **Step 2:** `01-create-databases.sh` (`#!/usr/bin/env bash`, `set -euo pipefail`): using `psql -v ON_ERROR_STOP=1 -U postgres`, create roles `heimdall`/`keycloak` with `LOGIN PASSWORD` from env, and databases `heimdall` OWNER heimdall and `keycloak` OWNER keycloak. Pass passwords via `psql -v` variables, never string concatenation.
- [ ] **Step 3:** `.env.example` with the three passwords set to `change-me-dev-only`, plus a header comment saying the values are dev-only and `.env` is gitignored.
- [ ] **Step 4: Verify both DBs exist with correct owners**
  Run: `cp infra/.env.example infra/.env && docker compose -f infra/docker-compose.yml up -d --wait && docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -tAc "select datname||':'||pg_get_userbyid(datdba) from pg_database where datname in ('heimdall','keycloak') order by 1"`
  Expected: `heimdall:heimdall` and `keycloak:keycloak`
- [ ] **Step 5: Verify data persists across recreation (Review Focus 1)**
  Run: `psql` create table `persist_check` in `heimdall`; `docker compose down` (no `-v`); `up -d --wait`; `select count(*) from persist_check`.
  Expected: `0` (table exists). Then drop it.
- [ ] **Step 6: Commit** `feat(infra): add postgres with app and keycloak databases`

---

### Task 3: Backend scaffold

**Files:**
- Create (generated): `backend/` from Spring Initializr, including `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/main/java/io/github/eerlij/heimdall/HeimdallApplication.java`, `src/test/.../TestcontainersConfiguration.java`, `src/test/.../TestHeimdallApplication.java`
- Create: `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-dev.yml`
- Delete: generated `application.properties`
- Test: `backend/src/test/java/io/github/eerlij/heimdall/HeimdallApplicationTests.java` (generated `contextLoads`)

**Interfaces:**
- Produces: `./mvnw verify` as the backend's single build command; profile `dev`; Testcontainers `@ServiceConnection` Postgres reused by every later integration test via `@Import(TestcontainersConfiguration.class)`.

- [ ] **Step 1: Generate**
  Run (repo root):
  `curl -sf https://start.spring.io/starter.zip -d type=maven-project -d language=java -d bootVersion=4.1.1 -d javaVersion=25 -d groupId=io.github.eerlij -d artifactId=heimdall -d name=heimdall -d packageName=io.github.eerlij.heimdall -d dependencies=web,actuator,validation,data-jpa,flyway,postgresql,modulith,testcontainers -o backend.zip && unzip -q backend.zip -d backend && rm backend.zip`
  Expected: `backend/pom.xml` with parent `4.1.1` and the `spring-modulith-bom` imported.
- [ ] **Step 2:** Set the Testcontainers Postgres image in `TestcontainersConfiguration` to `postgres:18`.
- [ ] **Step 3:** `application.yml`: app name `heimdall`; `server.port: 8080`; datasource `url: ${DB_URL:jdbc:postgresql://localhost:5432/heimdall}`, `username: ${DB_USERNAME:heimdall}`, `password: ${HEIMDALL_DB_PASSWORD}` (**no default**); `spring.jpa.open-in-view: false`; `spring.jpa.hibernate.ddl-auto: validate`; `spring.threads.virtual.enabled: true`; `server.shutdown: graceful`; `spring.mvc.problemdetails.enabled: true`; `management.server.port: 8081`; `management.endpoints.web.exposure.include: health,info,metrics`; `management.endpoint.health.probes.enabled: true`.
- [ ] **Step 4:** `application-dev.yml`: `spring.config.import: optional:file:../infra/.env[.properties]` (loads the dev passwords when run from `backend/`).
- [ ] **Step 5: Run the generated test**
  Run: `cd backend && ./mvnw -B -ntp verify`
  Expected: `BUILD SUCCESS`, `contextLoads` passes against a Testcontainers Postgres 18.
- [ ] **Step 6: Verify the dev run against compose**
  Run: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then `curl -s localhost:8081/actuator/health`
  Expected: `{"status":"UP",...}` including the `livenessState`/`readinessState` groups. Then run without `-Dspring-boot.run.profiles=dev`: startup fails with a message naming `HEIMDALL_DB_PASSWORD` (Review Focus 5).
- [ ] **Step 7:** Confirm `git ls-files --eol backend/mvnw` shows `i/lf` (Review Focus 3).
- [ ] **Step 8: Commit** `feat(backend): scaffold Spring Boot 4.1 app with Postgres`

---

### Task 4: Modules, boundaries and error handling

**Files:**
- Create: `backend/src/main/java/io/github/eerlij/heimdall/{identity,settings,notifications,shared}/package-info.java`
- Create: `backend/src/main/java/io/github/eerlij/heimdall/shared/web/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/io/github/eerlij/heimdall/ModularityTests.java`, `.../ArchitectureTests.java`, `.../shared/web/GlobalExceptionHandlerTests.java`

**Interfaces:**
- Produces: module packages (later plans add code only inside these); `shared` is an `@ApplicationModule(type = Type.OPEN)` other modules may depend on; `GlobalExceptionHandler extends ResponseEntityExceptionHandler` with `@ExceptionHandler(Exception.class) ProblemDetail handleUnexpected(Exception ex)`, which later plans extend with module-specific handlers.

- [ ] **Step 1: Write the failing tests**
  ```java
  // ModularityTests
  @Test void verifiesModuleStructure() { ApplicationModules.of(HeimdallApplication.class).verify(); }
  @Test void writesDocumentation() { new Documenter(ApplicationModules.of(HeimdallApplication.class)).writeDocumentation(); }

  // ArchitectureTests (ArchUnit, @AnalyzeClasses(packages = "io.github.eerlij.heimdall", importOptions = DoNotIncludeTests.class))
  @ArchTest ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
  @ArchTest ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
  @ArchTest ArchRule noLocalDateTimeOrDate = noClasses().should().dependOnClassesThat()
      .belongToAnyOf(java.time.LocalDateTime.class, java.util.Date.class);

  // GlobalExceptionHandlerTests (@WebMvcTest with a test-only @RestController under /api/test/boom throwing
  // new IllegalStateException("secret internal detail"))
  @Test void unknownApiPathReturnsProblem404()  // GET /api/does-not-exist → 404, Content-Type application/problem+json, $.status == 404
  @Test void unexpectedErrorReturnsGenericProblem500() // GET /api/test/boom → 500, problem+json,
      // $.title == "Internal Server Error", $.detail == "An unexpected error occurred.",
      // body does not contain "secret internal detail" and does not contain "Exception"
  ```
- [ ] **Step 2: Run to verify failure**
  Run: `./mvnw -B -ntp test -Dtest='ModularityTests,ArchitectureTests,GlobalExceptionHandlerTests'`
  Expected: compile failure (`GlobalExceptionHandler` missing) and the archunit dependency is missing.
- [ ] **Step 3:** Add `com.tngtech.archunit:archunit-junit5:1.5.1` (test scope) to `pom.xml`.
- [ ] **Step 4:** Create the four `package-info.java` files, each with a Javadoc sentence stating the module's responsibility (identity: users and authentication; settings: per-user preferences; notifications: web push; shared: cross-cutting web/infra code), with `shared` annotated `@ApplicationModule(type = ApplicationModule.Type.OPEN)`.
- [ ] **Step 5:** Implement `GlobalExceptionHandler` (`@RestControllerAdvice`) in `shared.web`: `handleUnexpected` logs at ERROR with the exception (SLF4J) and returns `ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "An unexpected error occurred.")`. Add class and method Javadoc.
- [ ] **Step 6: Run to verify pass**
  Run: same command as Step 2.
  Expected: all PASS; `target/spring-modulith-docs/` contains generated module docs.
- [ ] **Step 7: Commit** `feat(backend): add modulith modules, architecture rules and RFC 9457 errors`

---

### Task 5: Architecture decision records

**Files:**
- Create: `docs/adr/0002-monorepo-and-modular-monolith.md`, `0003-postgresql-and-flyway.md`, `0004-keycloak-bff-authentication.md`, `0005-angular-with-material.md`, `0006-observability-otel-grafana-faro.md` (including the **Faro-as-strictly-necessary** justification), `0007-amplitude-eu-behind-consent.md`, `0008-runtime-i18n-transloco-en-pt-pt.md`, `0009-hand-written-typescript-api-types.md` (drift risk accepted), `0010-container-first-run-modes.md`, `0011-keycloak-config-as-code.md`

- [ ] **Step 1:** Write each ADR (Status: Accepted, 2026-10-16) from the spec's "Decisions made by the user" and "Calls made by Claude". Context = why, Decision = the choice, Consequences = trade-offs named in the spec (e.g. 0009: types can drift silently, mitigated only by E2E tests; 0007: consent banner required, Amplitude project must be created in the EU region).
- [ ] **Step 2: Verify** every ADR has all four sections: `grep -L "## Consequences" docs/adr/0*.md` → no output.
- [ ] **Step 3: Commit** `docs: record foundation architecture decisions`

---

### Task 6: Formatting and coverage in the build

**Files:**
- Modify: `backend/pom.xml`

**Interfaces:**
- Produces: `./mvnw verify` fails on unformatted code; JaCoCo XML at `backend/target/site/jacoco/jacoco.xml` (consumed by SonarQube in plan 7); `./mvnw spotless:apply` as the fix command.

- [ ] **Step 1:** Add `spotless-maven-plugin` 3.10.4: java → `palantirJavaFormat`, `removeUnusedImports`, `importOrder`; pom → `sortPom`; `check` goal bound to `verify`.
- [ ] **Step 2:** Add `jacoco-maven-plugin` 0.8.15: `prepare-agent` + `report` (XML and HTML) at `verify`. No threshold here (the gate lives in SonarQube, plan 7).
- [ ] **Step 3: Verify formatting enforcement**
  Run: add a badly indented line to `HeimdallApplication.java`, then `./mvnw -B -ntp verify`
  Expected: FAIL with a Spotless violation. Then `./mvnw spotless:apply && ./mvnw -B -ntp verify` → `BUILD SUCCESS` and `target/site/jacoco/jacoco.xml` exists.
- [ ] **Step 4: Commit** `build(backend): enforce formatting and produce coverage reports`

---

### Task 7: Container image and `full` profile

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`
- Modify: `infra/docker-compose.yml`, `README.md`

**Interfaces:**
- Produces: compose service `backend` (profile `full`) with app on `8080` (internal network only) and management on `127.0.0.1:8081`; later plans place nginx in front of it.

- [ ] **Step 1:** Multi-stage `Dockerfile`: build stage `eclipse-temurin:25-jdk` runs `./mvnw -B -ntp package -DskipTests` with a BuildKit cache mount for `~/.m2`, then `java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted`. Runtime stage `eclipse-temurin:25-jre` copies the layers (dependencies first), runs as a non-root user (`useradd -r heimdall`), `ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","org.springframework.boot.loader.launch.JarLauncher"]`, `EXPOSE 8080 8081`.
- [ ] **Step 2:** `.dockerignore`: `target/`, `.idea/`, `*.iml`.
- [ ] **Step 3:** Compose service `backend`: `build: ../backend`, `profiles: [full]`, env `DB_URL=jdbc:postgresql://postgres:5432/heimdall` and `HEIMDALL_DB_PASSWORD` from `.env`, `depends_on: postgres: condition: service_healthy`, ports `127.0.0.1:8081:8081`.
- [ ] **Step 4: Verify**
  Run: `docker compose -f infra/docker-compose.yml --profile full up -d --build --wait` (with a 120s timeout), then `curl -s localhost:8081/actuator/health` and `docker compose -f infra/docker-compose.yml exec backend id -u`
  Expected: status `UP`; uid is not `0`.
- [ ] **Step 5:** README: add a "Run modes" section (`dev` = compose + `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`; `full` = `--profile full`).
- [ ] **Step 6: Commit** `feat(infra): containerize backend with full compose profile`

---

### Task 8: CI and dependency updates

**Files:**
- Create: `.github/workflows/backend.yml`, `.github/dependabot.yml`

**Interfaces:**
- Produces: required status check name **`backend / build`** (branch protection is configured in plan 7).

- [ ] **Step 1:** `backend.yml`: `name: backend`; triggers `push` to `main` and `pull_request`, both with path filters `backend/**` and `.github/workflows/backend.yml`; `permissions: contents: read`; `concurrency` group per ref with cancel-in-progress. Job `build` on `ubuntu-latest`: `actions/checkout@v5`, `actions/setup-java@v5` (temurin, 25, `cache: maven`), `./mvnw -B -ntp verify` in `backend/`, then upload `target/site/jacoco/` as an artifact.
- [ ] **Step 2:** `dependabot.yml` (version 2): ecosystems `maven` (`/backend`), `docker` (`/backend` and `/infra`), `github-actions` (`/`); weekly; grouped minor+patch updates per ecosystem.
- [ ] **Step 3: Verify locally** that the YAML parses: `docker run --rm -v "$PWD:/repo" rhysd/actionlint:latest -color` from the repo root. Expected: no errors.
- [ ] **Step 4: Commit** `ci: build and test backend on GitHub Actions; add Dependabot`
- [ ] **Step 5: Verify in GitHub (needs the user's go-ahead to push)**
  Push the branch and open a PR. Expected: the `backend / build` check is green.

---

## Done when
- `docker compose up -d` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` → health `UP`.
- `./mvnw verify` is green: context loads on PG18, Modulith verify, ArchUnit rules, RFC 9457 tests, Spotless, JaCoCo report.
- `--profile full` runs the backend container as non-root with health `UP`.
- The PR shows a green `backend / build` check.
