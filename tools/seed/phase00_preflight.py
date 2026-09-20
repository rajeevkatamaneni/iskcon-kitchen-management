#!/usr/bin/env python3
"""
Phase 00 — preflight.

Writes nothing. It answers, before any of the other phases waste an hour finding out the hard
way: is the API there, can every account this simulation needs actually sign in, is it the right
temple, and does the calendar cover the days we are about to plan meals on.

The last one is the reason this phase exists at all. The calendar is precomputed forward and
nothing computes it on request, so a window that starts before the precompute did produces meals
with no festival, no Ekadashi and no day type — and it produces them silently. Better to be told
now than to find out at phase 12.

    python3 tools/seed/phase00_preflight.py --api http://localhost:8091 --tenant <id>

Exit 0 if everything needed is in place, 1 otherwise.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note, warn  # noqa: E402
from common.config import (  # noqa: E402
    DONORS, KITCHEN_STAFF, TEMPLE_ADMIN, VOLUNTEERS, kitchen_manager,
)

PHASE = "phase00"


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 00 — preflight")
    problems = 0

    # ---- the API is there ------------------------------------------------
    step("the API")
    try:
        admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)
    except Exception as e:
        warn(f"cannot sign in as the Temple Admin: {e}")
        tally.problem("no Temple Admin session — nothing else can be checked")
        tally.report()
        return 1

    who = admin.whoami
    info(f"signed in as {who.get('fullName')} ({who.get('role')})")
    info(f"temple: {who.get('tenantName')}  timezone {who.get('timezone')}")

    if who.get("tenantId") != args.tenant:
        tally.problem(
            f"--tenant is {args.tenant} but this account resolves to {who.get('tenantId')}. "
            f"Everything would be seeded into the wrong temple.")
        problems += 1
    if who.get("role") != "TEMPLE_ADMIN":
        tally.problem(f"expected TEMPLE_ADMIN, got {who.get('role')}")
        problems += 1

    # ---- every account the simulation acts as ----------------------------
    step("the accounts")
    # Not "this account must be a manager" — which temple has one, and who it is, differs
    # between environments. What matters is that somebody can approve an ingredient request.
    manager = kitchen_manager(args.api, args.tenant, needs_approval=True)
    wanted = [(TEMPLE_ADMIN, "TEMPLE_ADMIN")]
    if manager != TEMPLE_ADMIN:
        wanted.append((manager, "KITCHEN_MANAGER"))
    wanted += [(e, "KITCHEN_STAFF") for e in KITCHEN_STAFF]
    wanted += [(e, "VOLUNTEER") for e in VOLUNTEERS + DONORS]

    for email, expected in wanted:
        try:
            session = sign_in(args.api, email, args.tenant)
            got = session.whoami.get("role")
            if got != expected:
                warn(f"{email}: signed in but is {got}, expected {expected}")
                tally.problem(f"{email} has role {got}, the scripts assume {expected}")
                problems += 1
            else:
                short = email.split("@")[0]
                kitchen = session.whoami.get("kitchenName") or "no kitchen"
                info(f"{short:28} {got:16} {kitchen}")
        except Exception as e:
            warn(f"{email}: {e}")
            tally.problem(f"{email} cannot be used — {e}")
            problems += 1

    # ---- the shape of the temple -----------------------------------------
    step("the temple")
    kitchens = admin.get("/api/v1/kitchens")
    planners = [k for k in kitchens if k.get("usesMealPlanner")]
    info(f"{len(kitchens)} kitchen(s), {len(planners)} of them planning meals")
    for k in kitchens:
        flags = []
        if k.get("isMain"):
            flags.append("main")
        if k.get("usesMealPlanner"):
            flags.append("plans meals")
        info(f"  {k['name']:20} {', '.join(flags) or 'receives only'}")
    if not planners:
        tally.problem("no kitchen uses the meal planner, so no meal can be saved at all")
        problems += 1

    kinds = admin.get("/api/v1/meal-kinds")
    info(f"meal kinds: {', '.join(k['name'] for k in kinds)}")
    by_name = {k["name"].lower(): k for k in kinds}
    for needed in ("breakfast", "lunch", "dinner"):
        if needed not in by_name:
            tally.problem(f"no meal kind called {needed} — phase 05 plans three meals a day")
            problems += 1
    if not any(k.get("isEvent") for k in kinds):
        tally.problem("no meal kind with isEvent — outside events cannot be planned")
        problems += 1

    vendors = admin.get("/api/v1/vendors")
    info(f"{len(vendors)} vendor(s)")
    if len(vendors) < 3:
        tally.problem(f"only {len(vendors)} vendors; the ordering phases want several")
        problems += 1

    # /api/v1/staff itself is not a route — the roster is /register. (Learned the hard way:
    # the bare path answers 404 KMS-400030, which reads like a missing temple rather than a
    # missing endpoint.)
    # The register answers {"current": [...], "former": [...]}, not a bare list.
    register = admin.get("/api/v1/staff/register")
    roster = register.get("current", []) if isinstance(register, dict) else register
    info(f"{len(roster)} staff currently employed")

    planner_kitchens = {k["id"] for k in planners}
    by_kitchen: dict[str, int] = {}
    for s in roster:
        by_kitchen[s.get("kitchenName") or "(none)"] = by_kitchen.get(s.get("kitchenName") or "(none)", 0) + 1
    for name, n in sorted(by_kitchen.items()):
        info(f"  {name:20} {n}")

    unplaced = [s for s in roster if not s.get("kitchenId")]
    if unplaced:
        tally.problem(
            f"{len(unplaced)} staff have no kitchen. Since the 'which kitchen is cooking' change "
            f"every staff row needs one, and the planner refuses anyone whose kitchen does not "
            f"plan meals (KMS-400183).")
        problems += 1

    # A guessed kitchen, flagged by the backfill. Not fatal — a Temple Admin can plan regardless —
    # but it decides whether kitchen staff can, so it is worth seeing before phase 05.
    unchecked = [s for s in roster if s.get("kitchenNeedsCheck")]
    if unchecked:
        note(f"{len(unchecked)} staff have a kitchen that was guessed by the backfill and never "
             f"confirmed; phase 04 looks at these")

    if not any(s.get("kitchenId") in planner_kitchens for s in roster):
        tally.problem(
            "no employed staff belong to a kitchen that plans meals, so only the Temple Admin "
            "could save one")
        problems += 1

    # ---- the calendar covers the window ----------------------------------
    step("the calendar over the window")
    missing = []
    for day in args.dates():
        got = admin.call("GET", f"/api/v1/calendar/{day.isoformat()}", allow=(204,))
        if got is None:
            missing.append(day)

    if missing:
        tally.problem(
            f"{len(missing)} of {len(args.dates())} days have no calendar row "
            f"({missing[0]} to {missing[-1]}). Meals on those days get no festival, no Ekadashi "
            f"and no day type. The precompute only runs forward; there is no backfill.")
        problems += 1
    else:
        info(f"all {len(args.dates())} days present, "
             f"{args.window_from} to {args.window_to}")

    occasions = admin.get(
        f"/api/v1/occasions/resolved?from={args.window_from}&to={args.window_to}")
    if occasions:
        info(f"{len(occasions)} named occasion(s) in the window:")
        for o in occasions:
            info(f"  {o['date']}  {o['name']:32} {o.get('defaultServings') or '-'} servings")
    else:
        tally.problem("no named occasions resolve in this window — no festival meals to plan")
        problems += 1

    ekadashi = [d for d in admin.get(
        f"/api/v1/calendar?from={args.window_from}&to={args.window_to}") if d.get("isEkadashi")]
    if ekadashi:
        info(f"Ekadashi on {', '.join(d['date'] for d in ekadashi)}")
    else:
        note("no Ekadashi in the window — the Ekadashi flag will not be exercised")

    # ---- what the other phases will build on -----------------------------
    step("what is already there")
    for label, path in (("ingredients", "/api/v1/ingredients"),
                        ("recipes", "/api/v1/recipes"),
                        ("inventory items", "/api/v1/inventory/items"),
                        ("purchase orders", "/api/v1/purchase-orders")):
        rows = admin.get(path)
        n = len(rows) if isinstance(rows, list) else "?"
        info(f"{label:18} {n}")
        if n:
            note(f"{label} is not empty; phases are re-runnable but a Day-1 reset gives a "
                 f"cleaner simulation")

    library = admin.get("/api/v1/library/recipes?limit=1")
    states = admin.get("/api/v1/library/recipes/states")
    total = sum(s.get("recipes", 0) for s in states)
    info(f"recipe library: {total} recipe(s) across {len(states)} book(s)")
    if total == 0:
        tally.problem("the recipe library is empty — phase 02 has nothing to import")
        problems += 1

    tally.report()
    if problems:
        warn(f"{problems} problem(s). Fix these before running the other phases.")
        return 1
    info("preflight clean — the other phases can run")
    return 0


if __name__ == "__main__":
    sys.exit(main())
