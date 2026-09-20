#!/usr/bin/env python3
"""
Size the opening stock against what the planned month actually needs.

Run **after phase 05 has planned the meals and before any cooking is recorded.** It measures the
demand from the real plan — every planned dish, scaled from its recipe's base yield to the amount
being cooked — and corrects the opening count to match.

**Why the opening count has to carry it.** The shopping list only counts meals that are still
PLANNED and dated today or later (`ShoppingListService`: `plan_date >= CURRENT_DATE`). Most of
the window is behind us, so those weeks cannot generate an order however many rounds are run, and
should not be able to: buying retrospectively is not a thing a temple does. The food was either on
the shelf at the start or it came from nowhere, and "came from nowhere" shows up as
`USED_BEYOND_RECORDED_STOCK` on every stock screen.

**One rule for everything, no special cases.** 85% of the month's measured demand, rounded to a
figure a person would write. Ghee and coconut had been excluded from an earlier sizing because
their measured demand was corrupted by a unit-family mismatch — ghee quoted in litres against a
catalogue in kilograms, coconut in kilograms against a catalogue in pieces. That mismatch is gone
now the curated catalogue is loaded, so they are sized by the same rule as everything else rather
than by a rule invented for them.

**It corrects rather than recreates.** `POST /api/v1/inventory/items/{id}/adjustments` with
`COUNT_CORRECTION` is what a store does when it counts again and finds a different number, and it
leaves the movement history a reader can follow. Nothing is deleted.

    python3 tools/seed/01a-resize-opening-stock.py --api <url> --tenant <id> [--dry-run]
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase01a"
DATA = Path(__file__).resolve().parent / "data" / "catalogue.json"

# How much of the month sits on the shelf on the first morning. See the note above on why this is
# high for perishables: it is standing in for shopping the application cannot represent.
SHARE = 0.85

BASE = {"KG": 1000.0, "GM": 1.0, "L": 1000.0, "ML": 1.0, "PIECES": 1.0}


def tidy(value: float, unit: str = "") -> float:
    """
    A figure a person would actually write on a store card.

    **Anything counted in pieces comes back whole, rounded up.** You cannot hold 7.5 agarbatti, and
    the application refuses to save a fractional count of a counted thing. Up rather than down
    because these are reorder levels and opening counts: a warning a packet early is useful, a
    warning a packet late is not.
    """
    if unit.upper() == "PIECES":
        return float(math.ceil(value))
    if value >= 500:
        return float(round(value / 50) * 50)
    if value >= 50:
        return float(round(value / 5) * 5)
    if value >= 5:
        return float(round(value))
    return round(value, 1)


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 01a — size the opening stock to the plan")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    # ---- what the plan needs ----------------------------------------------
    step("measuring the month from the plan itself")
    recipes = {}
    for summary in admin.get("/api/v1/recipes"):
        recipes[summary["id"]] = admin.get(f"/api/v1/recipes/{summary['id']}")
    ingredients = {i["id"]: i for i in admin.get("/api/v1/ingredients")}

    meals = admin.get(f"/api/v1/meals?from={args.window_from}&to={args.window_to}")
    if isinstance(meals, dict):
        meals = meals.get("meals", [])
    if not meals:
        tally.problem("no meals planned in the window — run phase 05 first, or this measures "
                      "nothing and would size the store to zero")
        return tally.report()

    demand: dict[str, float] = {}
    dishes = 0
    for meal in meals:
        detail = admin.get(f"/api/v1/meals/{meal['mealId']}")
        for dish in detail.get("dishes") or []:
            recipe = recipes.get(dish.get("recipeId"))
            if not recipe or not recipe.get("baseYieldQty"):
                continue
            scale = float(dish.get("targetYield") or 0) / float(recipe["baseYieldQty"])
            if scale <= 0:
                continue
            dishes += 1
            for line in recipe.get("ingredients") or []:
                ingredient = ingredients.get(line["ingredientId"])
                if not ingredient:
                    continue
                canonical = (float(line["quantity"]) * scale
                             * BASE[line["unit"]] / BASE[ingredient["unit"]])
                demand[ingredient["id"]] = demand.get(ingredient["id"], 0.0) + canonical

    info(f"{len(meals)} meal(s), {dishes} dish(es), {len(demand)} ingredient(s) called for")

    # ---- what is on the shelf ---------------------------------------------
    step("against what the store holds")
    items = {i["ingredientId"]: i for i in admin.get("/api/v1/inventory/items")}
    catalogue = json.loads(DATA.read_text())
    by_name = {r["name"]: r for r in catalogue["ingredients"] + catalogue["supplies"]}

    raised = []
    for ingredient_id, needed in sorted(demand.items(), key=lambda kv: -kv[1]):
        ingredient = ingredients.get(ingredient_id)
        if not ingredient:
            continue
        spec = by_name.get(ingredient["name"])
        if spec and spec.get("not_bought"):
            continue                      # water is never stocked

        target = tidy(needed * SHARE, ingredient["unit"])
        item = items.get(ingredient_id)
        on_hand = float(item["onHand"]) if item else 0.0
        if target <= on_hand:
            continue
        raised.append((ingredient, item, on_hand, target, needed))

    info(f"{len(raised)} ingredient(s) are short of the month")
    for ingredient, item, on_hand, target, needed in raised[:14]:
        info(f"  {ingredient['name']:24} {on_hand:>9,.0f} -> {target:>9,.0f} {ingredient['unit']:6}"
             f"  (month needs {needed:,.0f})")
    if len(raised) > 14:
        info(f"  … and {len(raised) - 14} more")

    if args.dry_run:
        note("dry run — nothing adjusted, nothing written")
        tally.report()
        return 0

    # ---- correct the count -------------------------------------------------
    step("correcting the opening count")
    for ingredient, item, on_hand, target, needed in raised:
        key = f"{PHASE}.topup.{ingredient['name']}"
        if args.state.has(key):
            tally.kept("corrected")
            continue
        if not item:
            tally.problem(f"{ingredient['name']} has no inventory item to correct")
            continue

        price = ingredient.get("marketRate") or (by_name.get(ingredient["name"]) or {}).get("price")
        if not price:
            tally.problem(f"{ingredient['name']} has no price, so a positive adjustment is refused")
            continue

        try:
            admin.post(f"/api/v1/inventory/items/{item['itemId']}/adjustments", {
                "batchId": None,
                "quantity": round(target - on_hand, 3),
                "unit": ingredient["unit"],
                "reason": "COUNT_CORRECTION",
                "note": "Opening count corrected against the month's plan before cooking began.",
                "pricePerUnit": float(price),
            })
            args.state.put(key, True)
            tally.made("corrected",
                       f"{ingredient['name']:24} {on_hand:,.0f} -> {target:,.0f} "
                       f"{ingredient['unit']}")
        except ApiError as e:
            tally.problem(f"{ingredient['name']}: {e}")

        if spec := by_name.get(ingredient["name"]):
            spec["opening"] = target

    # ---- and the reorder threshold, which is stale the moment the count changes ----
    #
    # Phase 01 sets the threshold to a quarter of the opening count. Correct the count without
    # correcting the threshold and the low-stock screen goes quiet: ghee sitting at 490 Kg with a
    # threshold of 11 would not warn until there was a day's cooking left, which is worse than no
    # warning at all because it looks like one.
    # Driven off the demand, not off `raised`: once the counts are right nothing is short, and a
    # threshold pass that loops over the short list then silently does nothing. It did exactly
    # that the first time, which is why this is a separate loop over everything the plan calls
    # for rather than a tail on the correction.
    step("the reorder thresholds that went stale with the count")
    for ingredient_id, needed in sorted(demand.items(), key=lambda kv: -kv[1]):
        ingredient = ingredients.get(ingredient_id)
        item = items.get(ingredient_id)
        if not ingredient or not item:
            continue
        spec = by_name.get(ingredient["name"])
        if spec and spec.get("not_bought"):
            continue
        target = tidy(needed * SHARE, ingredient["unit"])
        key = f"{PHASE}.threshold.{ingredient['name']}"
        if args.state.has(key):
            tally.kept("threshold")
            continue
        wanted = tidy(target / 4, ingredient["unit"])
        current = float(item.get("reorderThreshold") or 0)
        if abs(wanted - current) < max(1.0, wanted * 0.1):
            continue
        try:
            admin.put(f"/api/v1/inventory/items/{item['itemId']}", {
                "storageLocation": item.get("storageLocation"),
                "reorderThreshold": wanted,
                "notes": item.get("notes"),
            })
            args.state.put(key, True)
            tally.made("threshold",
                       f"{ingredient['name']:24} {current:,.0f} -> {wanted:,.0f} "
                       f"{ingredient['unit']}")
        except ApiError as e:
            tally.problem(f"threshold for {ingredient['name']}: {e}")

    # Write the new figures back so a fresh temple starts right rather than needing this phase.
    DATA.write_text(json.dumps(catalogue, indent=2, ensure_ascii=False) + "\n")
    info(f"catalogue.json updated, so a future phase 01 opens at these figures")

    step("the store now")
    items = admin.get("/api/v1/inventory/items")
    low = admin.get("/api/v1/inventory/items/low-stock")
    info(f"{len(items)} tracked item(s), {len(low)} below their reorder threshold")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
