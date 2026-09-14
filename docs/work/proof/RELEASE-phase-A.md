# Phase A release — T-195 to T-199 (D-27 meal rebuild), 2026-09-14

Release agent. Staging only. No Meta call, no message sent.

## Commit and gate
- Commit `74d3535a8b8b9eaf3e64ee71d44a349116f5cbbf`, 130 files, named paths only. The staged list was diffed against `git status --porcelain -uall` first: identical, nothing unaccounted for.
- Gate on `git archive HEAD` in `/tmp/kms-verify` with `git init && git add -A`:
  - backend `./gradlew test` (JDK 21, Docker 29.7.2): `BUILD SUCCESSFUL in 4m 12s`, 2664 passed, 0 failed, 7 skipped.
  - frontend `npm ci`, `tsc --noEmit` exit 0, `npm test` 138 files / 1850 tests passed, `npm run build` compiled, exit 0.
- CI run https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/34827586907: **success**. Repository (hygiene), Backend, Frontend all success.

## Backup (taken before deploy)
- Cloud SQL instance `kms-staging-5325bd0d`, on-demand backup id **`1789378310032`**, 2026-09-14T09:31:50Z, SUCCESSFUL, description "Before Phase A (D-27 meal reset V135-V137), commit 74d3535, 2026-09-14".

## Deploy
- `./deploy.sh iskcon-kms-2026 staging` from a clean tree at `74d3535` (checked in the same command). Builds 6m11s, rollouts 2m19s, total 8m32s. No error or fail line in the log.
- Before: api `kms-staging-api-00165-h2t` `sha256:9d30f2be1e64…0251f`; worker `00147-l6j` same image; web `kms-staging-web-00153-pfk` `sha256:2ceda8e68a2f…993d`.
- After, each at 100% traffic:
  - api `kms-staging-api-00166-8j8` `sha256:05e093895fb9052302f1fb5b2eab56270b0c9ced3371c0250cef81b8dd9f0f36`
  - worker `kms-staging-worker-00148-x2j`, same api image
  - web `kms-staging-web-00154-874` `sha256:45ec52641120c82396fbe68a2552cabec4b65d101471908d71cc3835c2cc3b13`
- Evidence it landed, not the exit code: both digests changed; the web chunk for `/volunteers/new` contains "Kitchen help for a meal? Ask from that meal"; `/planner/meal/<id>` answers 200; `/api/v1/meals` answers 401 (exists, needs a token). No ERROR log entries on api-00166 or worker-00148 in the hour after.

## Migrations (API startup log, api-00166)
```
Successfully validated 134 migrations
Current version of schema "public": 134
Migrating schema "public" to version "135 - meal data reset"
DB: V135 meal data reset, temple f935450b-1b7c-4b2c-a7e3-73e40c7e31e3: 113 dishes, 27 shifts, 12 job cards, 1 scheduled jobs, 973 stock movements removed; 165 balancing adjustments written
Migrating schema "public" to version "136 - a meal is a row of its own"
Migrating schema "public" to version "137 - a minimum of meals to test with"
DB: V137 reseed, temple f935450b-1b7c-4b2c-a7e3-73e40c7e31e3: 28 meals from 2026-09-12 to 2026-09-20, meal shift b6e525e7-5547-4d25-95f6-c8985be2cb39, volunteer signed up: t
Successfully applied 3 migrations to schema "public", now at version v137
```
V135, V136 and V137 applied. **V138, V139 and V140 do not exist**: reserved in the ledger and unused by all three builders, so there was nothing to apply. Next free migration V141.

## On-hand stock before and after
Method: a throwaway Cloud Run job (`postgres:16-alpine`, `kms_app`, VPC connector `kms-staging-vpc`, deleted afterwards) looped over `tenants` with `set_config('app.tenant_id', id, true)` and printed, per temple, per ingredient and batch, `SUM(to_on_hand_qty(quantity, unit, movement_type))` from `stock_movements`: the formula `MealRebuildMigrationIT.onHand()` uses. Plus one digest line per temple. Read only.

Staging has one temple (`iskcon-south-bengaluru`).

- before (09:34 UTC, execution `kms-phasea-onhand-mr5z4`): 264 rows, total 271776314.500, md5 c70c9cc3a933140ba5073518f8088bff
- after (post-deploy, execution `kms-phasea-onhand-mxddh`): 263 rows, total 271776314.500, md5 85367cc87bb295f7f7d65a84e943f172

`diff` of the per-batch rows, before against after, gives exactly one line:
```
< ONHAND|iskcon-south-bengaluru|2ed2b3fe-ac17-4517-80e2-cb8b8102ec23|Curd|ac0ed9ae-6e10-4163-8052-ae498b0eaf4d|0.000
```
That Curd batch stood at **0.000** before. Every movement on it was meal-connected and they netted to zero, so V135 deleted them and wrote no adjustment ("a net of zero writes nothing"). The batch now has no movement rows, which is still 0 on hand. The other 263 rows are identical to the last digit, and the total is unchanged. **On-hand matched.** The md5 differs only because that zero row left the string.

## Not done
- Not driven in a browser. The browser test in `docs/work/NEXT-SESSION.md` Phase A follows.
