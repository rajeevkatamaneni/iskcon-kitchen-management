#!/usr/bin/env python3
"""
Remove the vendored recipe books from the library, leaving only Rajeev's curated catalogue.

**Why this is not one SQL statement.** `master_recipes` is behind FORCE row-level security, and
V68 makes deleting from the library an operator's act alone — only an ACTIVE `SUPER_ADMIN`. A
Cloud Run job connecting as `kms_migration` sees an empty table, because the policy hides it; the
obvious workaround, having the job adopt a super admin's identity, is exactly the security control
the policy exists to impose. `DELETE /api/v1/library/recipes/{id}` as a signed-in super admin **is**
the intended path, and the audit row each delete writes is the point of it rather than a side
effect.

So this is slow on purpose: about 1.1 seconds a row, a little over an hour and a half for the
5,331 rows the old books left behind. Run it in the background.

**It never deletes a row it has not just read.** The rule is `source_ref` does not start with
`tools/seed/02b-build-catalogue.mjs`, and the check happens per row, immediately before the
delete — not from a list of names, not inferred from which book a row sits in. If a curated recipe
had somehow lost its stamp, deleting by "not one of the 44 I know about" would destroy Rajeev's own
work; reading the stamp cannot.

    python3 tools/seed/02c-drop-old-library.mjs.py --api <url> [--dry-run] [--survey-only]
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, sign_in, step, info, note, warn  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

SUPER_ADMIN = "ikms.super-admin.1@trading4good.org"
KEEP_PREFIX = "tools/seed/02b-build-catalogue.mjs"

# Stop rather than grind through thousands of failures.
MAX_CONSECUTIVE_FAILURES = 10
MIN_ATTEMPTS_BEFORE_RATE_CHECK = 100
MAX_FAILURE_RATE = 0.05


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--survey-only", action="store_true")
    args = parser.parse_args()

    admin = sign_in(args.api, SUPER_ADMIN)
    if admin.whoami.get("role") != "SUPER_ADMIN":
        warn(f"signed in as {admin.whoami.get('role')}, not SUPER_ADMIN — the library delete "
             f"needs the operator role")
        return 2

    # ---- enumerate ---------------------------------------------------------
    step("what the library holds")
    states = admin.get("/api/v1/library/recipes/states")
    total = sum(s.get("recipes", 0) for s in states)
    info(f"{total} recipe(s) across {len(states)} book(s)")

    rows = []
    for state in sorted(states, key=lambda s: s["slug"]):
        listing = admin.get(f"/api/v1/library/recipes?state={state['slug']}&limit=500")
        rows.extend((state["slug"], r) for r in listing)
    info(f"enumerated {len(rows)} row(s)")
    if len(rows) != total:
        warn(f"enumerated {len(rows)} but the states index says {total}; "
             f"some book may hold more than one page")

    # ---- survey: how many does the predicate actually select? --------------
    #
    # Checked against the live data rather than assumed. Only the two books the converter wrote
    # can hold a curated stamp, so those are read in full; the rest are counted as old here and
    # then still read one by one before anything is deleted.
    step("how many rows the predicate selects")
    curated_books = {"karnataka", "andhra_pradesh"}
    kept = 0
    for slug, row in rows:
        if slug not in curated_books:
            continue
        detail = admin.get(f"/api/v1/library/recipes/{row['id']}")
        if (detail.get("sourceRef") or "").startswith(KEEP_PREFIX):
            kept += 1
    selected = len(rows) - kept
    info(f"{kept} row(s) carry the curated stamp and stay")
    info(f"{selected} row(s) do not and will go")

    if args.survey_only or args.dry_run:
        note("survey only — nothing deleted")
        return 0

    # ---- delete ------------------------------------------------------------
    step(f"deleting {selected} row(s), checking each one's stamp first")
    started = time.time()
    removed = skipped = failed = 0
    consecutive = 0

    for index, (slug, row) in enumerate(rows, start=1):
        try:
            detail = admin.get(f"/api/v1/library/recipes/{row['id']}")
        except ApiError as e:
            if e.status == 404:
                skipped += 1          # already gone
                continue
            failed += 1
            consecutive += 1
            warn(f"could not read {row['displayName']}: {e}")
        else:
            stamp = detail.get("sourceRef") or ""
            if stamp.startswith(KEEP_PREFIX):
                skipped += 1
                consecutive = 0
                continue
            try:
                admin.delete(f"/api/v1/library/recipes/{row['id']}")
                removed += 1
                consecutive = 0
            except ApiError as e:
                failed += 1
                consecutive += 1
                warn(f"could not delete {row['displayName']}: {e}")

        if consecutive >= MAX_CONSECUTIVE_FAILURES:
            warn(f"{consecutive} failures in a row — stopping rather than grinding through the "
                 f"rest. {removed} removed so far.")
            return 1
        attempts = removed + failed
        if attempts >= MIN_ATTEMPTS_BEFORE_RATE_CHECK and failed / attempts > MAX_FAILURE_RATE:
            warn(f"{failed} of {attempts} failed ({failed / attempts:.0%}) — stopping. "
                 f"{removed} removed so far.")
            return 1

        if index % 250 == 0:
            rate = (time.time() - started) / max(removed, 1)
            left = (selected - removed) * rate / 60
            info(f"{removed} removed, {skipped} kept, {failed} failed — "
                 f"{rate:.2f}s each, about {left:.0f} min left")

    info(f"finished: {removed} removed, {skipped} kept, {failed} failed, "
         f"{(time.time() - started) / 60:.1f} min")

    # ---- prove the end state, not the count of deletes ---------------------
    step("what the library holds now")
    states = admin.get("/api/v1/library/recipes/states")
    total = sum(s.get("recipes", 0) for s in states)
    info(f"{total} recipe(s) across {len(states)} book(s): "
         f"{', '.join(f'{s['slug']} {s.get('recipes')}' for s in states)}")

    stamps: dict[str, int] = {}
    for state in states:
        for row in admin.get(f"/api/v1/library/recipes?state={state['slug']}&limit=500"):
            detail = admin.get(f"/api/v1/library/recipes/{row['id']}")
            stamp = (detail.get("sourceRef") or "?").split(":")[0]
            stamps[stamp] = stamps.get(stamp, 0) + 1
    for stamp, n in sorted(stamps.items()):
        info(f"  {n:4} row(s) stamped {stamp}")

    found = admin.get("/api/v1/library/recipes?q=Akki%20Rotti&limit=3")
    if found:
        detail = admin.get(f"/api/v1/library/recipes/{found[0]['id']}")
        water = [l for l in detail["ingredients"] if l["name"] == "Water"]
        info(f"spot read — Akki Rotti water line: {water}")

    ok = total == 44 and len(states) == 2 and len(stamps) == 1 and failed == 0
    info("END STATE CORRECT" if ok else "END STATE NOT AS EXPECTED — read the numbers above")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
