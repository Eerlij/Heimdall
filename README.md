# Heimdall

A bridge to all the dimensions of life.

Heimdall is a personal life assistant: expenses, wishlists, todo lists, imports from social apps, recommendations
from your own data, and reminders. It is a Spring Boot modular monolith with an Angular PWA frontend.

## Documentation

- [Foundation design](docs/superpowers/specs/2026-10-16-foundation-design.md)
- [Implementation plans](docs/superpowers/plans/)
- [Architecture decision records](docs/adr/)

## Getting started

Prerequisites: Docker (with Compose v2) and JDK 25. Maven is not needed; the backend ships the Maven wrapper.

```bash
cp infra/.env.example infra/.env   # dev-only values; edit if you like
docker compose -f infra/docker-compose.yml up -d --wait
```

This starts PostgreSQL 18 with two databases: `heimdall` (the app) and `keycloak`, each owned by its own role,
plus Keycloak (realm applied by keycloak-config), Mailpit and an nginx origin on port 80.

> The database init script runs only on an empty volume. If you change passwords in `infra/.env` after the first
> start, re-initialize with `docker compose -f infra/docker-compose.yml down -v` (this **deletes** local data).

## Run modes

| Mode | Command | What runs |
|---|---|---|
| `dev` | `docker compose -f infra/docker-compose.yml up -d --wait`, then in `backend/`: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` | Postgres in Docker, backend on the host; dev secrets are read from `infra/.env` |
| `full` | `docker compose -f infra/docker-compose.yml --profile full up -d --build` | Postgres and the backend image, as in production; the backend container and its new Keycloak env boot. Browsing `full` through nginx (its backend upstream and back-channel URL) arrives in plan 7 |

### Dev mode

```bash
cp infra/.env.example infra/.env
docker compose -f infra/docker-compose.yml up -d --wait
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Browse <http://localhost> (nginx forwards to the backend on the host).

| What | Where |
|---|---|
| Mailpit (verification mails) | <http://localhost:8025> |
| Keycloak admin | <http://localhost/auth/admin> (`admin`, password is `KEYCLOAK_ADMIN_PASSWORD` in `infra/.env`) |

`infra/.env` gained `KEYCLOAK_ADMIN_PASSWORD`, `HEIMDALL_KEYCLOAK_CLIENT_SECRET`, `PUBLIC_ORIGIN`,
`KC_REGISTRATION_ALLOWED`, `HEIMDALL_COOKIE_SECURE`, `BACKEND_UPSTREAM` and `BACKCHANNEL_LOGOUT_URL`; copy the new keys
from `infra/.env.example`. No volume reset is needed: the `keycloak` database has existed since the first setup.

Backend health: <http://127.0.0.1:8081/actuator/health> (the management port is local-only in both modes: dev binds it to
127.0.0.1, `full` publishes it on 127.0.0.1).

The app port 8080 stays reachable from Docker on purpose: Keycloak's back-channel logout calls `host.docker.internal:8080`.

Backend build: `./mvnw verify` in `backend/` runs tests (Testcontainers, so Docker must be running), the Spotless format
check and the JaCoCo coverage report. Fix formatting with `./mvnw spotless:apply`.

### Behind a TLS-intercepting antivirus or proxy

If Maven or the image build fails with `PKIX path building failed`, your antivirus (for example AVG/Avast HTTPS
scanning) or a corporate proxy is re-signing TLS traffic.

- Host builds: trust the Windows certificate store for Maven, e.g.
  `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT ./mvnw verify`.
- Image builds: copy the proxy's root CA to `infra/certs/local-ca.pem` (gitignored) and set
  `EXTRA_CA_CERT_FILE=./certs/local-ca.pem` in `infra/.env`. It is trusted only while building, never at runtime.

## License

[MIT](LICENSE)
