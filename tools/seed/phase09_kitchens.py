#!/usr/bin/env python3
"""
Phase 09 — the sister kitchens.

Rajeev's brief: "start adding satellite kitchens at least have five kitchens make two of them use
the meal planner and three of them are just going to get ingredients then start raising request
for ingredients for the 3 kitchens that are just requesting ingredients."

So five kitchens besides the main one: two that plan their own meals, three that only draw
ingredients from the temple store. The split is not cosmetic — the application enforces it in both
directions:

- a kitchen that plans its own meals **cannot raise an ingredient request** (KMS-400110), because
  it buys for itself;
- a kitchen that does not plan **cannot be put on a meal** (KMS-400181), and its staff cannot open
  the planner at all (KMS-400183).

The temple already has four of the five from earlier work, so this phase fills in what is missing
rather than starting over, and sets `usesMealPlanner` to match the plan above.

**Each kitchen also gets somebody in charge**, because a request has to come from a person and the
planner gate reads a staff record's kitchen. Without that, phase 10 has nobody who can ask.

    python3 tools/seed/phase09_kitchens.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase09"

# The five besides Main Kitchen. Two plan, three only draw from the store.
KITCHENS = [
    {"name": "Deity Kitchen", "plans": False,
     "description": "Cooks the offerings for the deities. Draws everything from the main store.",
     "location": "Behind the sanctum"},
    {"name": "Govindas Bliss", "plans": False,
     "description": "The restaurant counter. Asks the store for what the day's menu needs.",
     "location": "Temple gate, east side"},
    {"name": "Health Kitchen", "plans": True,
     "description": "Sattvic diet kitchen for the ashram residents. Plans its own meals.",
     "location": "Ashram block, ground floor"},
    {"name": "Annadana Kitchen", "plans": True,
     "description": "Free food distribution to the neighbourhood. Plans its own meals and cooks "
                    "with the main kitchen on festival days.",
     "location": "Distribution shed, north gate"},
    {"name": "Individual Person", "plans": False,
     "description": "Devotees cooking an offering at home for a sponsored occasion. Collects "
                    "ingredients from the store against a request.",
     "location": "Off site"},
]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 09 — the sister kitchens")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    kitchens = admin.get("/api/v1/kitchens")
    existing = {k["name"]: k for k in kitchens}
    sisters = [k for k in kitchens if not k.get("isMain")]
    info(f"{len(kitchens)} kitchen(s) already: {', '.join(sorted(existing))}")

    # **Use the kitchens the temple already has.** The brief asks for "at least five kitchens",
    # two of which plan their own meals — it does not ask for these particular names. Creating the
    # list below regardless of what is there put "Govindas Bliss" next to staging's own "Govindas
    # Restaurant": the same kitchen twice, under two names, on a screen Rajeev would open.
    #
    # So when the temple already has enough sisters, nothing is created and only the planner flags
    # are set to give the shape the brief asks for. Names are matched without regard to case or
    # surrounding space, because "Deity kitchen" and "Deity Kitchen" are not two kitchens either.
    folded = {k["name"].strip().lower(): k for k in kitchens}
    enough = len(sisters) >= 5
    if enough:
        note(f"{len(sisters)} sister kitchen(s) already — the brief asks for five, so none are "
             f"created. Only the meal-planner flags are set.")

    # Somebody to put in charge of each. The staff register is the source, so the person named
    # really works here rather than being invented.
    register = admin.get("/api/v1/staff/register")
    roster = register.get("current", []) if isinstance(register, dict) else register
    candidates = [s for s in roster if s.get("userId")]

    step("the sister kitchens")

    # Which of them plan and which draw. When the temple brought its own kitchens, the roles are
    # handed to those rather than to invented ones: the first two sisters by name plan, the rest
    # draw from the store.
    if enough:
        plan_for = {k["name"] for k in sorted(sisters, key=lambda k: k["name"])[:2]}
        wanted_list = [{"name": k["name"], "plans": k["name"] in plan_for,
                        "description": k.get("description"), "location": k.get("location")}
                       for k in sisters]
    else:
        wanted_list = KITCHENS

    for position, wanted in enumerate(wanted_list):
        found = folded.get(wanted["name"].strip().lower())
        in_charge = candidates[position % len(candidates)]["userId"] if candidates else None

        payload = {
            "name": wanted["name"],
            "description": wanted["description"],
            "location": wanted["location"],
            "usesMealPlanner": wanted["plans"],
            "isMain": False,
            "inChargeUserId": in_charge,
            "contactPhone": f"+9198450320{position + 10}",
        }

        if args.dry_run:
            verb = "would update" if found else "would create"
            info(f"{verb} {wanted['name']} — "
                 f"{'plans its own meals' if wanted['plans'] else 'draws from the store'}")
            continue

        try:
            if found:
                # Only the planner flag and the details are set; the kitchen keeps its id, so
                # every meal, staff record and request already pointing at it stays pointing.
                admin.put(f"/api/v1/kitchens/{found['id']}", payload)
                args.state.put(f"{PHASE}.kitchen.{wanted['name']}", found["id"])
                changed = found.get("usesMealPlanner") != wanted["plans"]
                tally.made("kitchen updated",
                           f"{wanted['name']} — "
                           + ("now plans its own meals" if wanted["plans"] and changed
                              else "now draws from the store" if changed
                              else "details filled in"))
            else:
                made = admin.post("/api/v1/kitchens", payload)
                args.state.put(f"{PHASE}.kitchen.{wanted['name']}", made["id"])
                tally.made("kitchen",
                           f"{wanted['name']} — "
                           f"{'plans its own meals' if wanted['plans'] else 'draws from the store'}")
        except ApiError as e:
            tally.problem(f"{wanted['name']}: {e}")

    # ---- what the temple looks like now -----------------------------------
    step("the kitchens")
    kitchens = admin.get("/api/v1/kitchens")
    planners = [k for k in kitchens if k.get("usesMealPlanner")]
    drawers = [k for k in kitchens if not k.get("usesMealPlanner")]

    for kitchen in sorted(kitchens, key=lambda k: (not k.get("isMain"), k["name"])):
        marks = []
        if kitchen.get("isMain"):
            marks.append("main")
        marks.append("plans its own meals" if kitchen.get("usesMealPlanner")
                     else "draws from the store")
        info(f"  {kitchen['name']:22} {', '.join(marks)}")

    info(f"{len(planners)} plan their own meals, {len(drawers)} draw from the store")

    if len(kitchens) < 6:
        tally.problem(f"only {len(kitchens)} kitchens; the brief asks for the main one and five more")
    if len(planners) < 3:
        note("the brief asks for two sister kitchens on the planner, plus the main one")
    if len(drawers) < 3:
        tally.problem(f"only {len(drawers)} kitchens draw from the store; phase 10 needs three")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
