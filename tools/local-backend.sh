#!/usr/bin/env bash
# Start the local API on :8080 against the compose database, with every outbound service off.
# Log: ~/Library/Logs/kms-local/backend.log
set -euo pipefail
cd "$(dirname "$0")/../backend"
mkdir -p ~/Library/Logs/kms-local
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export KMS_FIREBASE_ENABLED=true DB_MIGRATION_USER=kms_migration DB_MIGRATION_PASSWORD=kms_migration
# Safety: no scheduler, no email relay, no tenant secrets, no paid or external providers.
export KMS_WORKER_ENABLED=false SMTP_HOST= SECRETS_STORE=memory PAYMENTS_PROVIDER=stub \
  DOCUMENTS_STORAGE=local DOCUMENTS_RENDERER=stub TRANSLATION_PROVIDER=stub GEOCODING_PROVIDER=none \
  TRAVEL_TIME_PROVIDER=none PLACES_PROVIDER=none STATIC_MAP_PROVIDER=none SENTRY_DSN=
nohup ./gradlew bootRun > ~/Library/Logs/kms-local/backend.log 2>&1 &
# Recompile on every save; DevTools (developmentOnly) restarts the running app in seconds.
nohup ./gradlew -t classes -x test > ~/Library/Logs/kms-local/backend-compile.log 2>&1 &
echo "backend starting, pid $! — tail -f ~/Library/Logs/kms-local/backend.log"
