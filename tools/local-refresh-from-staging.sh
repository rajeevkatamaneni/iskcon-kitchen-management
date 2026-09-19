#!/usr/bin/env bash
# Replace the local compose database with a copy of staging, then make the copy safe.
#
# Read-only on staging: `gcloud sql export sql` is a Cloud SQL export operation, it writes nothing to
# the staging database. Stop the local backend before running this; the local `kms` database is dropped.
#
# One-time setup (needs someone allowed to change bucket IAM):
#   gcloud storage buckets create gs://iskcon-kms-2026-kms-local-refresh --project iskcon-kms-2026 \
#     --location asia-south1 --uniform-bucket-level-access
#   gcloud storage buckets add-iam-policy-binding gs://iskcon-kms-2026-kms-local-refresh \
#     --member=serviceAccount:$(gcloud sql instances describe kms-staging-5325bd0d \
#       --project iskcon-kms-2026 --format='value(serviceAccountEmailAddress)') \
#     --role=roles/storage.objectAdmin
set -euo pipefail

PROJECT=iskcon-kms-2026
INSTANCE=kms-staging-5325bd0d
BUCKET=gs://iskcon-kms-2026-kms-local-refresh
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/kms-local-refresh"
STAMP=$(date +%Y%m%d-%H%M%S)
OBJ="$BUCKET/staging-$STAMP.sql.gz"
mkdir -p "$WORK"

if lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Stop the local backend (port 8080) first." >&2; exit 1
fi

echo "== Exporting staging database 'kms' to $OBJ"
gcloud sql export sql "$INSTANCE" "$OBJ" --database=kms --project "$PROJECT" --quiet
gcloud storage cp "$OBJ" "$WORK/staging.sql.gz"
gcloud storage rm "$OBJ" --quiet

echo "== Recreating local database"
docker compose -f "$HERE/docker-compose.yml" up -d postgres
until docker exec kms-postgres pg_isready -U kms -d kms >/dev/null 2>&1; do sleep 1; done
docker exec -i kms-postgres psql -U kms -d postgres -v ON_ERROR_STOP=1 <<'SQL'
DROP DATABASE IF EXISTS kms WITH (FORCE);
CREATE DATABASE kms OWNER kms;
-- Roles the Cloud SQL dump may name; created NOLOGIN so their statements apply harmlessly.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='cloudsqlsuperuser') THEN CREATE ROLE cloudsqlsuperuser NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='postgres') THEN CREATE ROLE postgres NOLOGIN; END IF;
END $$;
SQL
docker exec -i kms-postgres psql -U kms -d kms -v ON_ERROR_STOP=1 -c "GRANT ALL ON SCHEMA public TO kms_migration;"

echo "== Loading dump (as the local superuser, so RLS does not filter the load)"
gunzip -c "$WORK/staging.sql.gz" | docker exec -i kms-postgres psql -U kms -d kms -q 2> "$WORK/load-errors.log" >/dev/null || true
echo "   load messages: $WORK/load-errors.log ($(grep -c ERROR "$WORK/load-errors.log" || true) ERROR lines)"

echo "== Making the copy safe"
docker exec -i kms-postgres psql -U kms -d kms -v ON_ERROR_STOP=1 < "$HERE/tools/local-safety.sql"

echo "== Handing ownership to kms_migration (README), so Flyway can migrate"
docker exec -i kms-postgres psql -U kms -d kms -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE obj RECORD;
BEGIN
    FOR obj IN SELECT format('%I', c.relname) AS name,
                      CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
                                     WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END AS kind
               FROM pg_class c
               WHERE c.relnamespace = 'public'::regnamespace
                 AND c.relkind IN ('r','p','S','v','m')
                 AND pg_get_userbyid(c.relowner) <> 'kms_migration'
    LOOP
        EXECUTE format('ALTER %s public.%s OWNER TO kms_migration', obj.kind, obj.name);
    END LOOP;

    FOR obj IN SELECT p.oid::regprocedure AS sig FROM pg_proc p
               WHERE p.pronamespace = 'public'::regnamespace
                 AND pg_get_userbyid(p.proowner) <> 'kms_migration'
    LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO kms_migration', obj.sig);
    END LOOP;
END $$;
GRANT ALL ON SCHEMA public TO kms_migration;

SQL

echo "== Done. Start the backend; Flyway migrates on top as kms_migration."
