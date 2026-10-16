#!/usr/bin/env bash
# Creates the app and Keycloak databases with dedicated roles.
# Runs only when the data volume is empty (first start); see README to re-initialize.
set -euo pipefail

psql -v ON_ERROR_STOP=1 -U postgres \
  -v heimdall_pw="$HEIMDALL_DB_PASSWORD" \
  -v keycloak_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE heimdall LOGIN PASSWORD :'heimdall_pw';
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_pw';
CREATE DATABASE heimdall OWNER heimdall;
CREATE DATABASE keycloak OWNER keycloak;
SQL
