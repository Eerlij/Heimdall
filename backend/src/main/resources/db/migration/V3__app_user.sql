-- No PII by design: name and email live only in the ID token inside the session.
create table app_user (
    id               uuid primary key default gen_random_uuid(),
    keycloak_subject uuid        not null unique,
    created_at       timestamptz not null default now()
);
