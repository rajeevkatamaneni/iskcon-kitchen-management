# Release — Phase A fixes T-213, T-214, T-215, 2026-09-14

**Commit:** `6dd436c` (fix: the shift form names every box, Today names events, and a new meal counts its rostered crew), on top of `9ba6285`.
Staged by named paths only: the three tasks' contract files, `frontend/__tests__/shift-form-labels.test.tsx` (new), proofs T-213..T-215, `phase-A-browser.md`, `DISPATCH.md` (with the planned PHASE B section as it stood), `NEXT-SESSION.md`, `AFTER-UAT.md`. Nothing else was in the tree. No screenshots or scratchpad files.

**Migration:** none (no `.sql` in the diff; T-215's proof confirms no migration, error code or permission). No migration, no backup needed.

## Gate: `git archive HEAD` into `/tmp/kms-verify`, `git init && git add -A`

- Backend `./gradlew test` (JDK 21, Docker): `BUILD SUCCESSFUL in 4m 12s`, exit 0. JUnit XML over 201 suites: **tests=2674 skipped=7 failures=0 errors=0**.
- Frontend `npm ci`, `npx tsc --noEmit` clean; `npm test`: **Test Files 139 passed (139), Tests 1871 passed (1871)**; `npm run lint` (`eslint . --max-warnings=0`) exit 0; `npm run build` (`next build`) succeeded.

## CI

Run 34835335217 on `6dd436c`: https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/34835335217 — **success** (Repository, Backend (Spring Boot), Frontend (Next.js) all success).

## Deploy: `./deploy.sh iskcon-kms-2026 staging` from a clean tree at `6dd436c`

`DEPLOY EXIT=0`. Builds 6m19s, rollouts 2m25s, total 8m46s.

| Service | Before | After (100% traffic) |
|---|---|---|
| api | `kms-staging-api-00166-8j8` `sha256:05e09389…0f36` | `kms-staging-api-00167-6xb` `sha256:65f20d6aa27367a4f01cc3b0ed5dbd5f4fa5cddf6701921bb6ba24818ed524be` |
| web | `kms-staging-web-00154-874` `sha256:45ec5264…3b13` | `kms-staging-web-00155-9h6` `sha256:7a09aa79bf9eee9078502e7b59c7870b6218a37b2dc98d7c7c05086a68224dbe` |
| worker | — | `kms-staging-worker-00149-sbj` (same api image digest) |

Both digests changed.

## The new behaviour answers on staging

Signed in as Temple Admin (`ikms.temple-admin.1`, Firebase custom token, nothing written):

```
GET /api/v1/meal-crew/at?date=2026-09-21&readyBy=12:00
{"planDate":"2026-09-21","readyBy":"12:00:00","staffIn":2,"volunteers":0,"rostered":2} HTTP=200
GET /api/v1/meal-crew/nope-control   HTTP=404   (control: unknown path)
```

The live web bundle served for `/planner` contains `meal-crew/at` (chunk `9116-24ed60d5231c1497.js`), so the composer calling it is live. T-213 (shift form labels) and T-214 (Today event names) are frontend-only and ride in the same web image; not driven in a browser by this release.

## Found while checking

- `GET /api/v1/meal-crew/at?date=2026-09-21` with **no `readyBy`** returns **HTTP 500**, not a 400 with a KMS code. The composer never calls it without a Ready by ("Not counted yet" until one is typed), so no screen reaches it today. Left for the main session; not fixed in this release.

## Where Rajeev can see it

https://kms-staging-web-bnpkv5hfrq-el.a.run.app — Planner → Add a meal (an Event, People needed 5, a Ready by): section 4 Rostered and the Ask for volunteers prefill; Volunteer shifts → Post a shift (labels); Today (an event's own name).
