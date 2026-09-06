-- Local development roles, created before Flyway runs.
--
-- The application must not connect as a superuser, and this file exists because for a long time it
-- did. Postgres exempts a superuser from Row-Level Security entirely, so every tenant-scoped query
-- — which in this codebase means nearly all of them, since isolation is the database's job and not
-- the application's — returned *every* temple's rows on a developer's machine.
--
-- It surfaced on 2026-09-05 as a theme that would not stick: `SELECT selected_theme_id FROM
-- tenant_settings` has no WHERE clause because RLS is supposed to supply one, so locally it read
-- five temples' rows and took whichever came first. Four of the five had chosen no theme, so the
-- screen kept resetting to the default. The same hole made every other isolation behaviour
-- unverifiable locally, and a screen showing another temple's data would have looked like it worked.
--
-- AbstractIntegrationTest has created these two roles for the test database all along, for exactly
-- this reason. Local development simply never got the same treatment.
CREATE ROLE kms_app WITH LOGIN PASSWORD 'kms_app';
ALTER ROLE kms_app NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- Migrations run as the owner; the application role holds no DDL. Kept separate here so local
-- development has the same two-role shape as everything above it.
CREATE ROLE kms_migration WITH LOGIN PASSWORD 'kms_migration';
ALTER ROLE kms_migration NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
GRANT ALL ON SCHEMA public TO kms_migration;
