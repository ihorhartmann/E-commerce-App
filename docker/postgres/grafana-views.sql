-- What grafana_reader (docker/postgres/init-grafana-reader.sh) may read: the views of the grafana schema, nothing else.
-- Run by the grafana-views container on every start, once the order-service's migrations have created the tables; it
-- can be run again at any time.
--
-- Grafana's viewers can send any query to its datasources, so each view holds only the columns a dashboard needs: no
-- customer, no address. A view reads its table with its owner's rights, so grafana_reader needs no right on the
-- services' schemas at all - and has none: what an older database granted it there is taken back first.

REVOKE SELECT ON ALL TABLES IN SCHEMA orders FROM grafana_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA orders REVOKE SELECT ON TABLES FROM grafana_reader;
REVOKE USAGE ON SCHEMA orders FROM grafana_reader;

CREATE SCHEMA IF NOT EXISTS grafana;
GRANT USAGE ON SCHEMA grafana TO grafana_reader;

-- the saga dashboard's stuck orders
CREATE OR REPLACE VIEW grafana.orders AS
SELECT id, status, created_at, updated_at
FROM orders.orders;
GRANT SELECT ON grafana.orders TO grafana_reader;

-- a query that runs long, or a handful of them at once, can't slow down the shop's database
ALTER ROLE grafana_reader CONNECTION LIMIT 5;
ALTER ROLE grafana_reader SET statement_timeout = '5s';
