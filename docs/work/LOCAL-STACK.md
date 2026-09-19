# Local stack

A local copy of the app you can sign into with the real UAT accounts (Firebase project
`iskcon-kms-2026-620ee`). Sign-in goes to real Firebase; everything else is on this machine.

Ports: database 5432 (`kms-postgres` container), API http://localhost:8080, web http://localhost:3000.
Logs: `~/Library/Logs/kms-local/backend.log` and `frontend.log`.

## Start

```bash
docker compose up -d                      # database
tools/local-backend.sh                    # API; Flyway migrates as kms_migration on start
cd frontend && NEXT_PUBLIC_API_URL=http://localhost:8080 \
  nohup npm run dev > ~/Library/Logs/kms-local/frontend.log 2>&1 &
```

Check: `curl localhost:8080/health` shows `"db":"UP","scheduler":"STANDBY"`.

Server changes reload by themselves: save a Java file and the API restarts in about 6 seconds
(continuous compile + Spring DevTools, development-only). Frontend changes hot-reload in `next dev`.

## Stop

```bash
lsof -ti tcp:8080 | xargs kill            # API
pkill -f 'gradlew -t classes'             # API's recompile-on-save watcher
lsof -ti tcp:3000 | xargs kill            # web
docker stop kms-postgres                  # database (data kept in the volume)
```

## Refresh the data from staging

Stop the API first, then `tools/local-refresh-from-staging.sh`. It runs `gcloud sql export sql` (a
read-only export of the staging `kms` database), downloads it, drops and recreates the local `kms`
database, loads it as the local superuser, runs `tools/local-safety.sql`, and removes the export from
the bucket. Start the API again afterwards; Flyway applies whatever migrations the code has that
staging does not.

The export needs a bucket the staging Cloud SQL instance can write to. That bucket,
`gs://iskcon-kms-2026-kms-local-refresh`, **does not exist yet**: creating it and granting the
instance's service account write access is a permission change, and it was refused to the agent on
2026-09-17. The two commands are at the top of the refresh script. Until they are run, the local
database holds the old local test data (migrated to V142), not staging's, and the UAT accounts sign
in to Firebase but get "you don't have an account at any temple yet".

Why not `pg_dump` from a Cloud Run job on the VPC: every tenant table has FORCE row-level security,
and no staging role bypasses it, so a dump as `kms_app` or `kms_migration` comes back empty or fails.
The Cloud SQL export runs as Cloud SQL's own admin and gets every row.

## What is off, and what the copy has blanked

Off by configuration in `tools/local-backend.sh`: Quartz scheduler (`KMS_WORKER_ENABLED=false`, so no
queued message is ever sent and no nightly job runs), email (`SMTP_HOST` empty), tenant secrets
(`SECRETS_STORE=memory`, so no Meta token or Razorpay key secret exists locally), payments (`stub`),
PDF (`stub`, local storage), translation (`stub`), maps/geocoding/routes/places (`none`), Sentry (no
DSN). SMS has no provider in the code at all. The backend never writes to Firebase; it only verifies
tokens.

Blanked in the database by `tools/local-safety.sql`, after every load:
- `tenant_settings`: every `whatsapp_*` column (phone number id, WABA id, webhook token, display
  number, verified/seen/submitted times) and every `payment_*` column (provider, key id, webhook
  token, verified/registered/seen times). The secrets themselves are in Secret Manager and never in
  the dump.
- All Quartz jobs and triggers copied from staging (`qrtz_*` except `qrtz_locks`).

Never start the local API with `KMS_WORKER_ENABLED=true`, `SECRETS_STORE=gcp` or an `SMTP_HOST`.

## Known

`/api/v1/today` is expected to fail with KMS-400001 on staging data until the bad dish (Basmati Ghee
Rice, 210,000 L, 19 Sep dinner) is fixed on staging and the data refreshed.
