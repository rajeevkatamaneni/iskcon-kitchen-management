#!/usr/bin/env python3
"""
Phase 11 — the festival meals two kitchens cook together.

Rajeev's brief: "two of the sister kitchens, which are going to use the meal planner will also be
planning their meals in conjunction with the main kitchen and so on and so forth. Not every meal
has to be a collaboration between multiple kitchens it can be a few meals, especially when those
meals are festival meals because that is when it makes sense for multiple kitchens to collaborate
because the menu will be longer."

So: only the festival meals, and only where the menu is long enough to be worth splitting. The
ordinary Tuesday lunch stays with one kitchen, which is the point he was making.

**Both 15-day blocks were already planned by phase 05**, which covers the whole window in one
pass, so there is no second block to plan here. What is left is the collaboration itself.

**How a shared meal works.** A meal has one section per kitchen in `kitchens: [{kitchenId,
crewRequired}]`, and every dish names which of them cooks it. The rules the application enforces,
each of which this phase has to respect:

- a kitchen that does not use the meal planner cannot be put on a meal at all (KMS-400181);
- a dish naming a kitchen that is not on its meal is refused (KMS-400182);
- **once a meal has two kitchens, every dish must name one** — the "leave it out and it goes to
  the only kitchen" shortcut stops working the moment a second one is added;
- an empty kitchen list is refused outright (KMS-400180).

So the update has to send the whole dish list with a kitchen on every line, which is what makes
this a rewrite of the meal rather than an addition to it.

    python3 tools/seed/phase11_joint_festivals.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import KITCHEN_MANAGER, TEMPLE_ADMIN  # noqa: E402

PHASE = "phase11"

# Who helps with what. The Annadana kitchen feeds the neighbourhood, so it takes the rice and the
# roti — the bulk items — while the main kitchen keeps the dishes that need watching.
HELPERS = ["Annadana Kitchen", "Health Kitchen"]
BULK_CATEGORIES = {"Rice", "Roti", "Khichadi"}


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 11 — festival meals cooked together")

    planner = sign_in(args.api, KITCHEN_MANAGER, args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    kitchens = {k["name"]: k for k in admin.get("/api/v1/kitchens")}
    main = next((k for k in kitchens.values() if k.get("isMain")), None)
    if not main:
        tally.problem("no main kitchen")
        return tally.report()

    helpers = [kitchens[name] for name in HELPERS
               if name in kitchens and kitchens[name].get("usesMealPlanner")]
    if not helpers:
        tally.problem("no sister kitchen uses the meal planner, so nothing can be shared "
                      "(KMS-400181). Run phase 09 first.")
        return tally.report()
    info(f"helping kitchens: {', '.join(k['name'] for k in helpers)}")

    recipes = {r["id"]: r for r in admin.get("/api/v1/recipes")}

    meals = planner.get(f"/api/v1/meals?from={args.window_from}&to={args.window_to}")
    if isinstance(meals, dict):
        meals = meals.get("meals", [])

    # Only the festival meals: the feast itself, and the breakfast and lunch on a festival day.
    festival_days = {m["planDate"] for m in meals if m.get("occasionName")}
    shared = [m for m in meals
              if m["planDate"] in festival_days
              and m.get("mealKind") in ("Festival feast", "Lunch")
              and not m.get("eventName")]
    info(f"{len(festival_days)} festival day(s), {len(shared)} meal(s) worth sharing")

    if args.dry_run:
        for meal in shared:
            info(f"would share {meal['planDate']} {meal['mealKind']}")
        tally.report()
        return 0

    step("splitting the long menus between two kitchens")
    for position, meal in enumerate(sorted(shared, key=lambda m: (m["planDate"], m["mealKind"]))):
        key = f"{PHASE}.shared.{meal['mealId']}"
        if args.state.has(key):
            tally.kept("shared meal")
            continue

        detail = planner.get(f"/api/v1/meals/{meal['mealId']}")
        dishes = detail.get("dishes") or []
        if len(dishes) < 3:
            tally.skip("shared meal",
                       f"{meal['planDate']} {meal['mealKind']} has only {len(dishes)} dish(es)")
            continue

        # Already shared? Leave it. Read from the meal rather than the ledger, for the same
        # reason phase 05 does: a ledger is a file on one machine and the meal is the truth.
        if len(detail.get("kitchens") or []) > 1:
            args.state.put(key, True)
            tally.kept("shared meal", f"{meal['planDate']} {meal['mealKind']}")
            continue

        helper = helpers[position % len(helpers)]
        plates = meal.get("plates") or 0

        # The helper takes the bulk dishes, the main kitchen keeps the rest. Crew is split the
        # same way: more hands where more dishes are.
        assigned, helper_count = [], 0
        for dish in dishes:
            recipe = recipes.get(dish.get("recipeId"), {})
            to_helper = recipe.get("categoryName") in BULK_CATEGORIES
            if to_helper:
                helper_count += 1
            assigned.append({
                "id": dish.get("dishId") or dish.get("id"),
                "recipeId": dish["recipeId"],
                "targetYield": dish.get("targetYield"),
                "kitchenId": helper["id"] if to_helper else main["id"],
            })

        if helper_count == 0 or helper_count == len(dishes):
            # Nothing to split, or everything would move. Either way it is not a collaboration.
            tally.skip("shared meal",
                       f"{meal['planDate']} {meal['mealKind']} has no bulk dish to hand over")
            continue

        payload = {
            "readyBy": detail.get("readyBy"),
            "eventName": detail.get("eventName"),
            "isOutside": bool(detail.get("isOutside")),
            "purpose": detail.get("purpose"),
            "occasionName": detail.get("occasionName"),
            "adults": detail.get("adults"), "children": detail.get("children"),
            "seniors": detail.get("seniors"),
            "kitchens": [
                {"kitchenId": main["id"],
                 "crewRequired": max(4, round(plates / 220))},
                {"kitchenId": helper["id"],
                 "crewRequired": max(2, round(plates / 400))},
            ],
            "kitchenNotes": (detail.get("kitchenNotes") or "")
                            + ("\n" if detail.get("kitchenNotes") else "")
                            + f"{helper['name']} is cooking the rice and rotis for this one. "
                              f"Vessels go back to the main kitchen by four.",
            "serverNotes": detail.get("serverNotes"),
            "ekadashiAcknowledged": True,
            "dishes": assigned,
        }
        try:
            planner.put(f"/api/v1/meals/{meal['mealId']}", payload)
            args.state.put(key, True)
            tally.made("shared meal",
                       f"{meal['planDate']} {meal['mealKind']:15} "
                       f"{detail.get('occasionName') or ''} — {helper['name']} takes "
                       f"{helper_count} of {len(dishes)} dishes")
        except ApiError as e:
            tally.problem(f"{meal['planDate']} {meal['mealKind']}: {e}")

    # ---- what the plan shows now ------------------------------------------
    step("meals with more than one kitchen")
    meals = planner.get(f"/api/v1/meals?from={args.window_from}&to={args.window_to}")
    if isinstance(meals, dict):
        meals = meals.get("meals", [])
    joint = 0
    for meal in sorted(meals, key=lambda m: m["planDate"]):
        detail = planner.get(f"/api/v1/meals/{meal['mealId']}")
        sections = detail.get("kitchens") or []
        if len(sections) < 2:
            continue
        joint += 1
        who = ", ".join(f"{s['kitchenName']} ({s.get('crewRequired') or '-'})" for s in sections)
        info(f"  {meal['planDate']} {meal['mealKind']:15} {who}")
    info(f"{joint} meal(s) are cooked by more than one kitchen")

    if joint == 0:
        tally.problem("nothing ended up shared")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
