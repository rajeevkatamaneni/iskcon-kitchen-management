#!/usr/bin/env python3
"""
Phase 05a — put the right hours on volunteer shifts that were seeded with the wrong ones.

Phase 05 used to give every shift but dinner the same window, 10:00 to 14:00. That produced a
**breakfast service at ten in the morning**, and on a festival day it put the breakfast, the lunch
and the feast in the identical four hours. Nobody can work three services at once, so the roster
also came out thin for a reason that had nothing to do with how many volunteers the temple has.

Phase 05 now takes the hours from the meal kind's own `defaultReadyTime` — the crew arrives two and
a half hours before the meal is ready and leaves two hours after it. This script applies the same
rule to shifts that were already created, through `PUT /api/v1/shifts/{id}`, which is what a
coordinator would do.

It matches a shift to its meal kind by the meal it is attached to, so it changes nothing it cannot
explain. A shift with no meal behind it is left exactly as it is: that is a shift somebody wrote by
hand, and its hours are theirs.

Re-runnable: a shift already on the right hours is counted and skipped.

    python3 tools/seed/05a-fix-shift-hours.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

# The one rule, imported rather than restated, so the two can never drift apart.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "phase05", Path(__file__).resolve().parent / "phase05_meal_plan.py")
_phase05 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_phase05)
shift_hours = _phase05.shift_hours

PHASE = "05a"


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("05a — shift hours")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    step("what each kind of meal is ready at")
    kinds = {k["id"]: k for k in admin.get("/api/v1/meal-kinds")}
    for k in sorted(kinds.values(), key=lambda k: k.get("sortOrder", 0)):
        start, end = shift_hours(k)
        info(f"{k['name']:16} ready {k.get('defaultReadyTime') or '(none)':9} "
             f"-> shift {start[:5]}–{end[:5]}")

    step("the shifts")
    shifts = admin.get("/api/v1/shifts")
    info(f"{len(shifts)} shift(s)")

    # A shift carries the name of its meal's kind, read through the meal rather than copied onto
    # the shift, so a renamed kind still matches. No second call needed.
    by_name = {k["name"]: k for k in kinds.values()}

    for shift in sorted(shifts, key=lambda s: (s["shiftDate"], s["startTime"])):
        if not shift.get("mealId"):
            tally.skip("shift with no meal behind it", shift["title"])
            continue
        kind = by_name.get(shift.get("mealKind"))
        if not kind:
            tally.problem(
                f"{shift['title']}: its meal's kind is {shift.get('mealKind')!r}, which this "
                f"temple does not list")
            continue

        start, end = shift_hours(kind)
        if shift["startTime"][:8] == start and shift["endTime"][:8] == end:
            tally.kept("shift already on the right hours", shift["title"])
            continue

        was = f"{shift['startTime'][:5]}–{shift['endTime'][:5]}"
        if args.dry_run:
            info(f"{shift['shiftDate']} {shift['title']}: {was} would become {start[:5]}–{end[:5]}")
            continue
        try:
            admin.put(f"/api/v1/shifts/{shift['id']}", {
                "title": shift["title"],
                "description": shift.get("description"),
                "shiftDate": shift["shiftDate"],
                "startTime": start,
                "endTime": end,
                "location": shift.get("location"),
                "capacity": shift["capacity"],
                "reminderOffsetsMinutes": shift.get("reminderOffsetsMinutes") or [1440, 120],
                # Sent back exactly as it came: a meal shift cannot be moved to another meal or
                # turned into a plain one (KMS-400153), and leaving the field out would drop the
                # link.
                "mealId": shift["mealId"],
            })
        except ApiError as e:
            tally.problem(f"{shift['title']}: {e}")
            continue
        tally.made("shift re-timed", f"{shift['shiftDate']} {shift['title']}: "
                                     f"{was} -> {start[:5]}–{end[:5]}")

    return tally.report()


if __name__ == "__main__":
    raise SystemExit(main())
