#!/usr/bin/env bash
# Start a SECOND local API for seeding work, on its own port and its own database copy,
# so the API on :8080 (and whatever database it is pointed at) is never touched.
#
#   tools/seed/local-seed-backend.sh            # port 8091, database kms_seed
#   SEED_PORT=8092 SEED_DB=kms_try tools/seed/local-seed-backend.sh
#
# Make the database copy first:
#   docker exec kms-postgres psql -U kms -d postgres -c "CREATE DATABASE kms_seed TEMPLATE kms_verify;"
#
# Log: ~/Library/Logs/kms-local/seed-backend.log
set -euo pipefail
cd "$(dirname "$0")/../../backend"
mkdir -p ~/Library/Logs/kms-local
PORT="${SEED_PORT:-8091}"
DB="${SEED_DB:-kms_seed}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export SERVER_PORT="$PORT"
export DB_URL="jdbc:postgresql://localhost:5432/${DB}"
export KMS_FIREBASE_ENABLED=true DB_MIGRATION_USER=kms_migration DB_MIGRATION_PASSWORD=kms_migration
# Same safety switches as tools/local-backend.sh: no scheduler, no email, no tenant secrets,
# no paid or external providers.
export KMS_WORKER_ENABLED=false SMTP_HOST= SECRETS_STORE=memory PAYMENTS_PROVIDER=stub \
  DOCUMENTS_STORAGE=local DOCUMENTS_RENDERER=stub TRANSLATION_PROVIDER=stub GEOCODING_PROVIDER=none \
  TRAVEL_TIME_PROVIDER=none PLACES_PROVIDER=none STATIC_MAP_PROVIDER=none SENTRY_DSN=
nohup ./gradlew bootRun --console=plain > ~/Library/Logs/kms-local/seed-backend.log 2>&1 &
echo "seed API starting on :${PORT} against ${DB} — tail -f ~/Library/Logs/kms-local/seed-backend.log"
