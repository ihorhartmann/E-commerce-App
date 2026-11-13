#!/bin/sh
# Grafana's own user, read-only: the dashboards list what Prometheus only counts - the stuck orders - from the
# database. The tables are created later, by the services' migrations, so what it may read - the views of the grafana
# schema, nothing else - is set afterwards, by grafana-views.sql. Its password is a Docker secret, at
# GRAFANA_DB_PASSWORD_FILE.
: "${GRAFANA_DB_PASSWORD_FILE:?GRAFANA_DB_PASSWORD_FILE is not set}"
reader_password="$(cat "$GRAFANA_DB_PASSWORD_FILE")"
: "${reader_password:?the secret at GRAFANA_DB_PASSWORD_FILE is empty}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v reader_password="$reader_password" <<'SQL'
CREATE ROLE grafana_reader LOGIN PASSWORD :'reader_password';
SQL
