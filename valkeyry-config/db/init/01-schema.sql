-- Pre-create the schema the Flyway migrations expect.
-- Postgres' docker-entrypoint-initdb.d runs this once on a fresh volume.
CREATE SCHEMA IF NOT EXISTS valkeyry_config AUTHORIZATION postgres;
GRANT ALL ON SCHEMA valkeyry_config TO postgres;
