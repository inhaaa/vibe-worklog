#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"

password_names=(POSTGRES_PASSWORD TNA_MIGRATION_DB_PASSWORD TNA_APP_DB_PASSWORD TNA_AUTH_DB_PASSWORD)
passwords=()
for name in "${password_names[@]}"; do
    value="${!name-}"
    if [[ -z "$value" || "$value" == CHANGE_ME* ]]; then
        printf '%s must be set to a local-only password.\n' "$name" >&2
        exit 1
    fi
    for previous in "${passwords[@]}"; do
        if [[ "$value" == "$previous" ]]; then
            printf 'Database roles must use different passwords.\n' >&2
            exit 1
        fi
    done
    passwords+=("$value")
done
unset value previous passwords

psql --no-psqlrc --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=database_name="$POSTGRES_DB" \
    --set=migration_password="$TNA_MIGRATION_DB_PASSWORD" \
    --set=app_password="$TNA_APP_DB_PASSWORD" \
    --set=auth_password="$TNA_AUTH_DB_PASSWORD" <<'SQL'
BEGIN;

CREATE ROLE tna_migration LOGIN PASSWORD :'migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE ROLE tna_app LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE ROLE tna_auth LOGIN PASSWORD :'auth_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"database_name" TO tna_migration;
GRANT CONNECT ON DATABASE :"database_name" TO tna_app, tna_auth;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO tna_migration;

COMMIT;
SQL
