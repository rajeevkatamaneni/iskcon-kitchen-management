#!/usr/bin/env python3
"""
Phase 04 — are the staff and their schedules fit to plan against?

Rajeev's brief: "Check on the staff schedules and make sure it's all set properly."

This is a check, not a build. It writes one thing and only when asked: confirming a kitchen the
backfill guessed. Everything else it reports, because a staff roster is a record of real people
and a seeding script inventing employment history is not something anyone asked for.

What it looks at, and why each one can stop a later phase:

- **Every employed person has a kitchen.** Required since the "which kitchen is cooking" change.
- **Somebody is in a kitchen that plans meals.** The planner refuses anyone else with
  403 KMS-400183, so without this only the Temple Admin could save a meal.
- **The weekly template covers the days we are about to plan.** A meal on a day nobody works is
  not wrong, exactly, but it is not a simulation of a real temple either.
- **Kitchens the backfill guessed.** `kitchenNeedsCheck` marks a row somebody should confirm.

    python3 tools/seed/phase04_staff_check.py --api http://localhost:8091 --tenant <id>
    python3 tools/seed/phase04_staff_check.py ... --confirm-kitchens
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase04"
DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]


def extra_args(parser) -> None:
    parser.add_argument(
        "--confirm-kitchens", action="store_true",
        help="confirm the kitchen on every staff row the backfill guessed")


def main() -> int:
    args = parse_args(PHASE, extra_args)
    tally = Tally("phase 04 — staff and schedules")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    kitchens = {k["id"]: k for k in admin.get("/api/v1/kitchens")}
    planners = {k_id for k_id, k in kitchens.items() if k.get("usesMealPlanner")}

    register = admin.get("/api/v1/staff/register")
    current = register.get("current", []) if isinstance(register, dict) else register
    former = register.get("former", []) if isinstance(register, dict) else []

    step("the roster")
    info(f"{len(current)} employed, {len(former)} former")
    by_title = Counter(s.get("jobTitleLabel") or s.get("jobTitle") or "?" for s in current)
    for title, n in sorted(by_title.items()):
        info(f"  {title:24} {n}")

    step("which kitchen each one belongs to")
    placed = Counter()
    for s in current:
        name = kitchens.get(s.get("kitchenId"), {}).get("name") or "(none)"
        placed[name] += 1
    for name, n in sorted(placed.items()):
        marker = "  plans meals" if any(
            k["name"] == name and k["id"] in planners for k in kitchens.values()) else ""
        info(f"  {name:24} {n}{marker}")

    unplaced = [s for s in current if not s.get("kitchenId")]
    if unplaced:
        tally.problem(
            f"{len(unplaced)} employed staff have no kitchen: "
            f"{', '.join(s['fullName'] for s in unplaced)}. Every staff row needs one.")

    in_planner = [s for s in current if s.get("kitchenId") in planners]
    if in_planner:
        info(f"{len(in_planner)} of them can use the meal planner")
    else:
        tally.problem(
            "nobody employed is in a kitchen that plans meals, so only the Temple Admin could "
            "save one (everyone else gets 403 KMS-400183)")

    # ---- kitchens the backfill guessed ------------------------------------
    step("kitchens nobody has confirmed")
    try:
        checks = admin.get("/api/v1/staff/kitchen-checks")
        pending = checks if isinstance(checks, list) else checks.get("staff", [])
    except ApiError:
        pending = [s for s in current if s.get("kitchenNeedsCheck")]

    if not pending:
        info("none — every staff row's kitchen has been confirmed")
    else:
        info(f"{len(pending)} row(s) carry a guessed kitchen:")
        for s in pending:
            info(f"  {s.get('fullName'):24} {s.get('kitchenName') or '(none)'}")

        if not args.raw.confirm_kitchens:
            note("run again with --confirm-kitchens to accept them, or set each one in the app")
            tally.skip("kitchen confirmation", f"{len(pending)} left for a person to decide")
        elif args.dry_run:
            tally.skip("kitchen confirmation", "dry run")
        else:
            # The check list calls it staffId; the register calls the same thing id.
            ids = [s.get("staffId") or s["id"] for s in pending]
            try:
                admin.post("/api/v1/staff/kitchen-checks/confirm", {"staffIds": ids})
                tally.made("kitchen confirmation", f"{len(ids)} row(s)", n=len(ids))
            except ApiError as e:
                tally.problem(f"could not confirm the guessed kitchens: {e}")

    # ---- the weekly template ---------------------------------------------
    step("the weekly shift template")
    covered = Counter()
    no_template = []
    for s in current:
        try:
            profile = admin.get(f"/api/v1/staff/profiles/{s['id']}")
        except ApiError as e:
            tally.problem(f"could not read {s['fullName']}'s profile: {e}")
            continue
        # dayOfWeek is an ISO number (1 = Monday), and a row exists for every day with a
        # `working` flag — so a template is not "the days they work", it is seven rows of which
        # some are false. Counting rows rather than working days would say everybody works
        # every day.
        template = profile.get("template") or []
        if not template:
            no_template.append(s["fullName"])
            continue
        for slot in template:
            if not slot.get("working"):
                continue
            iso = slot.get("dayOfWeek")
            if isinstance(iso, int) and 1 <= iso <= 7:
                covered[DAYS[iso - 1]] += 1

    for day in DAYS:
        n = covered.get(day, 0)
        flag = "" if n else "   <- nobody rostered"
        info(f"  {day.title():10} {n}{flag}")

    if no_template:
        tally.problem(
            f"{len(no_template)} employed staff have no weekly template: "
            f"{', '.join(no_template)}. Nothing stops a meal being planned, but the crew "
            f"figures on the job cards will be empty.")

    bare = [d for d in DAYS if not covered.get(d)]
    if bare:
        tally.problem(f"nobody is rostered on {', '.join(d.title() for d in bare)}, and the "
                      f"window being planned covers every day of the week")

    # ---- what the window will ask of them --------------------------------
    step("against the window being planned")
    info(f"{args.window_from} to {args.window_to}, {len(args.dates())} days")
    weekdays = Counter(d.strftime("%A").upper() for d in args.dates())
    thin = [d for d in DAYS if weekdays.get(d) and covered.get(d, 0) < 2]
    if thin:
        note(f"fewer than two people rostered on {', '.join(d.title() for d in thin)}")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
