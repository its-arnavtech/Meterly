#!/bin/sh
# ---------------------------------------------------------------------------------------------------------
# First-boot database bootstrap (runs once, on an empty data directory, as the container superuser).
#
#   meterly_owner  LOGIN, owns database `meterly` (and therefore its `public` schema). The migrator runs
#                  Flyway as this role, so it owns every table; RLS does not apply to the table owner.
#   meterly_app    LOGIN NOSUPERUSER NOBYPASSRLS, CONNECT only. Services connect as this role; table and
#                  function privileges are granted by the migrations (V2/V3), so RLS always applies.
#
# Nobody but the owner may create objects in `public`, and PUBLIC loses its default CONNECT/TEMP on the
# database. Role names and passwords come from the environment (see .env.example); psql's :'var' / format()
# quoting keeps them safe even if they contain quotes.
# ---------------------------------------------------------------------------------------------------------
set -eu

: "${DB_NAME:=meterly}"
: "${DB_OWNER_USERNAME:=meterly_owner}"
: "${DB_APP_USERNAME:=meterly_app}"
: "${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD must be set (see .env.example)}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD must be set (see .env.example)}"

echo "meterly-initdb: creating roles '${DB_OWNER_USERNAME}', '${DB_APP_USERNAME}' and database '${DB_NAME}'"

psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname postgres \
     -v db_name="$DB_NAME" \
     -v owner_user="$DB_OWNER_USERNAME" -v owner_password="$DB_OWNER_PASSWORD" \
     -v app_user="$DB_APP_USERNAME" -v app_password="$DB_APP_PASSWORD" <<'EOSQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
              :'owner_user', :'owner_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner_user') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT',
              :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user') \gexec

SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'db_name', :'owner_user', 'UTF8')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db_name') \gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db_name', :'app_user') \gexec
EOSQL

# Schema-level hardening has to run inside the target database.
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$DB_NAME" \
     -v owner_user="$DB_OWNER_USERNAME" <<'EOSQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER SCHEMA public OWNER TO %I', :'owner_user') \gexec
EOSQL

echo "meterly-initdb: done"
