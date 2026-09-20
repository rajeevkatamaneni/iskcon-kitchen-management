#!/usr/bin/env python3
"""
Phase 01 — what is on the shelf on the first morning.

Rajeev's brief puts this first and says why: "one of the first things for meal planning they have
to first figure out what stuff they have on hand and how much they have so let's do that first."
A temple counts its store before it plans a menu.

So this creates the catalogue — food AND supplies, because he asked for both — and gives every one
of them an opening count with the price the temple would pay today. Supplies are the gas cylinders,
the leaf plates, the floor cleaner: things that are bought and never cooked.

Two things worth knowing about the order this runs in.

**The names come from the curated recipes.** Every food name in data/catalogue.json is one the
approved recipes actually use, so when phase 02 imports them the ingredient is already there and is
reused rather than created a second time. Where the recipes name one thing two ways — Sabudana and
Sago, Cashew and Cashew nuts, Upma Rava and Semolina — the second is seeded as an **alias**, which
the application matches on. Without that the temple ends up holding one thing twice, which is the
exact defect the no-duplicate-ingredients work exists to stop.

**An opening count is one call, not two.** POST /api/v1/inventory/items with an `openingCount`
creates the tracked item and its first batch in a single transaction, and lifts the Temple-Admin
signature that a large adjustment would otherwise need. A refused count leaves no item behind.

    python3 tools/seed/phase01_opening_stock.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase01"
DATA = Path(__file__).resolve().parent / "data" / "catalogue.json"

# Where things live. A real store is not one room, and the low-stock screen reads better when the
# rows are not all in the same place.
LOCATION = {
    "Grains": "Dry store", "Pulses": "Dry store", "Sweeteners": "Dry store",
    "Spices": "Spice room", "Nuts & seeds": "Spice room",
    "Oils & fats": "Oil store", "Dairy": "Cold room", "Vegetables": "Cold room",
    "Fruit": "Cold room", "Other": "Dry store",
    "Cleaning": "Utility room", "Serving": "Serving store", "Fuel": "Gas yard",
    "Maintenance": "Utility room", "Puja": "Puja store",
}


def reorder_threshold(row: dict) -> float:
    """
    A quarter of the opening count, rounded to something a person would write.

    **A counted thing gets a whole number, rounded up.** "Tell me when agarbatti drops below 7.5"
    is not a sentence a storekeeper would write, and since the whole-counts rule shipped the
    application will not save the row at all. Up rather than down on purpose: a reorder level is a
    warning, and a warning that comes a packet early is useful where one that comes a packet late
    is not.
    """
    opening = float(row.get("opening") or 0)
    if opening <= 0:
        return 0
    rough = opening / 4
    if (row.get("unit") or "").upper() == "PIECES":
        return float(math.ceil(rough))
    if rough >= 100:
        return round(rough / 10) * 10
    if rough >= 10:
        return round(rough)
    return round(rough, 1)


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 01 — opening stock")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    catalogue = json.loads(DATA.read_text())
    rows = [dict(r, supply=False) for r in catalogue["ingredients"]]
    rows += [dict(r, supply=True) for r in catalogue["supplies"]]
    info(f"{len(catalogue['ingredients'])} food ingredients and "
         f"{len(catalogue['supplies'])} supplies to put on the shelf")

    if args.dry_run:
        for r in rows:
            kind = "supply" if r["supply"] else "food"
            info(f"would create {kind:7} {r['name']:22} {r['opening']} {r['unit']} "
                 f"at Rs {r['price']}/{r['unit']}")
        tally.report()
        return 0

    # ---- what is already in the catalogue --------------------------------
    # Read once. A re-run must find what an earlier run made even if the ledger was thrown away,
    # and the name is what the application itself matches on.
    existing = {i["name"].strip().lower(): i for i in admin.get("/api/v1/ingredients")}
    info(f"{len(existing)} ingredient(s) already in the catalogue")

    step("the catalogue")
    for row in rows:
        key = f"{PHASE}.ingredient.{row['name']}"
        known = existing.get(row["name"].strip().lower())

        if known:
            args.state.put(key, known["id"])
            tally.kept("ingredient", row["name"])
            continue
        if args.state.has(key):
            tally.kept("ingredient", row["name"])
            continue

        payload = {
            "name": row["name"],
            "category": row["category"],
            "unit": row["unit"],
            "supply": row["supply"],
            "ekadashiProhibited": bool(row.get("ekadashi")),
            # Water is the case. The catalogue marks it as something the temple never buys, and
            # this is the only chance to say so: **copying a library recipe will not set the mark
            # on an ingredient the temple already has.** That is deliberate — copying a recipe is
            # MANAGE_RECIPES, which a Kitchen Manager holds, while the buying policy is the Temple
            # Admin's alone, so the import refuses to flip a row it does not own and records
            # "notBoughtNotApplied" in its audit entry instead.
            #
            # This phase runs before the recipes are imported, so every ingredient already exists
            # by the time a recipe names it, and every mark would be refused. Leaving the key out
            # here put water on the temple's shopping list and left fifteen audit rows saying so.
            "notBought": bool(row.get("not_bought")),
            "aliases": row.get("aliases") or [],
        }
        try:
            made = admin.post("/api/v1/ingredients", payload)
            args.state.put(key, made["id"])
            tally.made("ingredient", f"{row['name']} ({row['unit']})")
        except ApiError as e:
            if e.code == "KMS-400034":
                # Already there under this exact name. The normal answer on a re-run.
                found = e.detail("existingIngredientId")
                if found:
                    args.state.put(key, found)
                tally.kept("ingredient", row["name"])
                continue
            if e.code == "KMS-400156":
                # Looks like something already in the catalogue. Deliberately NOT overridden with
                # confirmDifferent: a seeding script inventing a second Curd is the whole problem.
                # Reuse what it named and say so.
                other = e.detail("existingIngredientName") or "something"
                found = e.detail("existingIngredientId")
                if found:
                    args.state.put(key, found)
                    tally.kept("ingredient", f"{row['name']} -> reused {other}")
                else:
                    tally.problem(f"{row['name']} looks like {other} and no id came back")
                continue
            tally.problem(f"{row['name']}: {e}")

    # ---- pack sizes -------------------------------------------------------
    step("how each one is sold")
    for row in rows:
        ingredient_id = args.state.get(f"{PHASE}.ingredient.{row['name']}")
        if not ingredient_id:
            continue
        for pack in row.get("packs") or []:
            key = f"{PHASE}.pack.{row['name']}.{pack['name']}"
            if args.state.has(key):
                tally.kept("pack size")
                continue
            try:
                made = admin.post(f"/api/v1/ingredients/{ingredient_id}/pack-sizes", {
                    "name": pack["name"],
                    "quantity": pack["quantity"],
                    "unit": pack["unit"],
                })
                args.state.put(key, made["id"])
                tally.made("pack size",
                           f"{row['name']}: {pack['name']} = {pack['quantity']} {pack['unit']}")
            except ApiError as e:
                if e.code == "KMS-400157":
                    tally.kept("pack size", f"{row['name']} {pack['name']}")
                else:
                    tally.problem(f"pack {pack['name']} on {row['name']}: {e}")

    # ---- the count --------------------------------------------------------
    step("counting the store")
    counted = 0
    for row in rows:
        ingredient_id = args.state.get(f"{PHASE}.ingredient.{row['name']}")
        if not ingredient_id:
            continue

        opening = float(row.get("opening") or 0)
        if opening <= 0:
            # Water, and anything else the temple holds no stock of. An opening count must be
            # positive, so there is nothing to record — the ingredient exists and that is all.
            tally.skip("count", f"{row['name']} — nothing on the shelf")
            continue

        key = f"{PHASE}.stock.{row['name']}"
        if args.state.has(key):
            tally.kept("opening count", row["name"])
            continue

        payload = {
            "ingredientId": ingredient_id,
            "storageLocation": LOCATION.get(row["category"], "Dry store"),
            "reorderThreshold": reorder_threshold(row),
            "openingCount": {
                "quantity": opening,
                "unit": row["unit"],
                "pricePerUnit": row["price"],
            },
        }
        try:
            made = admin.post("/api/v1/inventory/items", payload)
            args.state.put(key, made["id"])
            counted += 1
            tally.made("opening count",
                       f"{row['name']:22} {opening:>8} {row['unit']:6} @ Rs {row['price']}")
        except ApiError as e:
            if e.code == "KMS-400040":
                tally.kept("opening count", row["name"])
            else:
                tally.problem(f"count for {row['name']}: {e}")

    # ---- what the store is worth -----------------------------------------
    step("the store as the application now sees it")
    items = admin.get("/api/v1/inventory/items")
    info(f"{len(items)} tracked item(s)")
    value = 0.0
    for row in rows:
        if row.get("opening"):
            value += float(row["opening"]) * float(row["price"])
    info(f"opening stock is worth about Rs {value:,.0f}")
    low = admin.get("/api/v1/inventory/items/low-stock")
    if low:
        note(f"{len(low)} item(s) already below their reorder threshold: "
             f"{', '.join(i['ingredientName'] for i in low[:5])}")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
