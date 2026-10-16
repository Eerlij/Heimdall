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

This starts PostgreSQL 18 with two databases: `heimdall` (the app) and `keycloak`, each owned by its own role.

> The database init script runs only on an empty volume. If you change passwords in `infra/.env` after the first
> start, re-initialize with `docker compose -f infra/docker-compose.yml down -v` (this **deletes** local data).
