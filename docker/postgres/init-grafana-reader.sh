#!/bin/sh
# Grafana's own user, read-only: the dashboards list what Prometheus only counts - the stuck orders - straight from the
# orders schema. Its tables are created later, by the order-service's migrations, so the grant also covers every table
# the owner creates there from now on.
: "${GRAFANA_DB_PASSWORD:?GRAFANA_DB_PASSWORD is not set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v reader_password="$GRAFANA_DB_PASSWORD" -v owner="$POSTGRES_USER" <<'SQL'
CREATE ROLE grafana_reader LOGIN PASSWORD :'reader_password';
GRANT USAGE ON SCHEMA orders TO grafana_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA orders TO grafana_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA orders GRANT SELECT ON TABLES TO grafana_reader;
SQL
