# Phase B release — T-200 to T-212 and T-216, 2026-09-14

Release agent. Staging only. No Meta call, no Reload pressed, no message sent.

## Commit and gate
- Commit `69da777804812f97222e061a8db72ea946543dd3`, 110 files, named paths only. Every entry in `git status --porcelain -uall` was matched to a task's path list (DISPATCH.md, including T-208's `planner-shift.test.tsx` and T-212's `CostByMealKind.java` widenings), a work-manager reservation (`ErrorCode.java`, `lib/api.ts`), or the docs the brief named. After staging, nothing was left unstaged or untracked.
- Gate on `git archive HEAD` in `/tmp/kms-verify`, with `git init && git add -A`:
  - backend `./gradlew test` (JDK 21, Docker 29.7.2): `BUILD SUCCESSFUL in 4m 19s`, 2,721 tests, 0 failures, 0 errors, 7 skipped (JUnit XML).
  - frontend fresh `npm ci`; `tsc --noEmit` exit 0; `npm run lint` exit 0; `npm test` 139 files / 1,900 tests passed; `npm run build` exit 0.
- CI run https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/34848217310: **success**. Repository, Backend (Spring Boot), Frontend (Next.js) all success.

## Backup (taken before deploy)
- Cloud SQL instance `kms-staging-5325bd0d`, on-demand backup id **`1789391583379`**, 2026-09-14T13:13:03Z, SUCCESSFUL, description "Before Phase B (V141 whatsapp_app_id, V142 template reasons), commit 69da777, 2026-09-14".

## Deploy
- `./deploy.sh iskcon-kms-2026 staging` from a clean tree at `69da777` (checked in the same command). Builds 6m45s, rollouts 2m30s, total 9m17s. No error or fail line in the log.
- Before: api `kms-staging-api-00167-6xb` `sha256:65f20d6a…24be`; worker `kms-staging-worker-00149-sbj` same image; web `kms-staging-web-00155-9h6` `sha256:7a09aa79…4dbe`.
- After, each at 100% traffic:
  - api `kms-staging-api-00168-zjc` `sha256:dd6a9716f8e7787167e740820829c848125c357355dde29c43b35a4c94637755`
  - worker `kms-staging-worker-00150-lnh`, same api image
  - web `kms-staging-web-00156-25b` `sha256:99cb7076b0ec719bb73949bb04db1c7b6bf47d4f3674b3791ee1f843a88545fb`
- Evidence it landed, not the exit code: both digests changed; `/actuator/health` 200; the web's Settings chunk (`page-914306ee2ba20543.js`) contains "App ID"; `/settings` and `/communications/new` answer 200. No ERROR log entries on api-00168, worker-00150 or web-00156 in the hour after.

## Migrations (API startup log, api-00168)
```
Successfully validated 136 migrations
Current version of schema "public": 137
Migrating schema "public" to version "141 - whatsapp app id"
Migrating schema "public" to version "142 - whatsapp reasons name the templates button"
DB: V142: rewrote the stored WhatsApp template reasons of 0 temple(s)
Successfully applied 2 migrations to schema "public", now at version v142
```
V141 and V142 applied. V142 found no stored "Press Reload" reason to rewrite on staging.

## Not done
- Not driven in a browser. The Phase B browser test follows.
- Whether Meta accepts the stored token for the `/uploads` sample (T-200) is still unchecked. It is for the main session, and was not tried here.
- T-216's call (`GET /api/v1/meal-crew/at` without `readyBy`) answered 401 with no token. It was not repeated as a signed-in user.
