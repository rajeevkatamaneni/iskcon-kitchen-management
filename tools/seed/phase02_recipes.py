#!/usr/bin/env python3
"""
Phase 02 — put the temple's recipes in.

Rajeev's brief: "your next job would be importing the recipes… I think you can import all the
recipes that are available in the master. Once the recipes are imported, then the ingredients will
get imported automatically."

It imports **the 45 recipes he approved**, by name, from `docs/work/reference/curated-recipes/`.
It finds each one in the shared library and imports it into the temple.

**The same script works before and after the catalogue is swapped**, which is the point of doing it
by name. Rajeev curated these *from* the vendored library, so the names resolve against the old
books today and against his catalogue once that is loaded. The only difference is which row is
found, and after the swap that row is the one carrying his preparations and not-bought marks.

**Every close match is answered "use the one already there".** The import asks before creating an
ingredient that resembles one in the catalogue, and phase 01 has already seeded the whole catalogue
with aliases. So the honest answer is always to reuse, and the script never sends
`confirmDifferent`. A seeding script that waved the guard through would manufacture the duplicate
ingredients the guard exists to prevent — which is the one mistake that would quietly spoil every
costed figure downstream.

    python3 tools/seed/phase02_recipes.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note, warn  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase02"
CURATED = Path(__file__).resolve().parents[2] / "docs" / "work" / "reference" / "curated-recipes"


def wanted_recipes() -> list[dict]:
    """The approved recipes, from the curation tool's own index."""
    index = CURATED / "index.json"
    if not index.exists():
        return []
    data = json.loads(index.read_text())
    approved = [r for r in data.get("recipes", []) if r.get("status") == "approved"]
    # The category comes from each recipe's own file. It is the third rung of the library's
    # disambiguation ladder and the only thing that separates the two Alugadde Palyas.
    for row in approved:
        recipe_file = CURATED / (row.get("file") or "")
        if recipe_file.exists():
            row["category"] = json.loads(recipe_file.read_text()).get("recipe", {}).get("cat", "")
    return approved


def find_in_library(admin, name: str, state: str, category: str = "") -> dict | None:
    """
    The library row for a recipe, by name, down the same ladder the library itself used.

    The loader disambiguates in up to three rungs: a name nobody else holds stays bare; one held by
    two states gets the state — "Upma (Andhra Pradesh)"; and one held twice *within* a state gets
    the category as well — "Alugadde Palya (Karnataka, Ekadashi)", which is a real case and the
    reason the third rung exists at all. The Karnataka book has two Alugadde Palyas, one for
    Ekadashi with rock salt and no mustard, one with a full tempering.

    So this tries the rungs in the same order: bare name, then name plus state, then the category
    from the curated file. Still ambiguous is reported rather than guessed at — picking the wrong
    one puts a tempered dish on an Ekadashi.
    """
    found = admin.get(f"/api/v1/library/recipes?q={name.replace(' ', '%20')}&limit=40")
    if not found:
        return None

    exact = [r for r in found if (r.get("displayName") or "").lower() == name.lower()]
    if len(exact) == 1:
        return exact[0]

    prefix = f"{name.lower()} ("
    by_state = [r for r in found
                if (r.get("state") or "").lower() == state.lower()
                and (r.get("displayName") or "").lower().startswith(prefix)]
    if len(by_state) == 1:
        return by_state[0]

    if category and len(by_state) > 1:
        by_category = [r for r in by_state
                       if (r.get("categoryName") or "").lower().replace("'", "").replace(",", "")
                       == category.lower().replace("'", "").replace(",", "")]
        if len(by_category) == 1:
            return by_category[0]
        # The category key is a slug ("sabjis-dry") against a display name ("Sabji's, Dry"),
        # so fall back to comparing only the letters.
        squash = lambda s: "".join(c for c in s.lower() if c.isalnum())  # noqa: E731
        by_category = [r for r in by_state
                       if squash(r.get("categoryName") or "") == squash(category)]
        if len(by_category) == 1:
            return by_category[0]

    if exact:
        return exact[0]
    if len(by_state) > 1:
        raise LookupError(
            f"{len(by_state)} library recipes are called {name} in {state}: "
            + "; ".join(f"{r['displayName']} [{r.get('categoryName')}]" for r in by_state))
    return None


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 02 — the temple's recipes")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    approved = wanted_recipes()
    if approved:
        info(f"{len(approved)} approved recipe(s) in {CURATED.name}")
    else:
        note(f"no curated index at {CURATED} — falling back to the Karnataka and Andhra books")
        states = {s["slug"]: s for s in admin.get("/api/v1/library/recipes/states")}
        approved = []
        for slug in ("karnataka", "andhra_pradesh"):
            if slug not in states:
                continue
            for r in admin.get(f"/api/v1/library/recipes?state={slug}&limit=40"):
                approved.append({"name": r["displayName"], "state": states[slug]["name"]})

    step("finding each one in the library")
    plan = []
    for row in approved:
        name, state = row["name"], row.get("state", "")
        key = f"{PHASE}.recipe.{name}"
        if args.state.has(key):
            tally.kept("recipe", name)
            continue
        try:
            found = find_in_library(admin, name, state, row.get("category", ""))
        except LookupError as e:
            tally.problem(f"'{name}' is ambiguous — {e}")
            continue
        if not found:
            tally.problem(f"'{name}' ({state}) is not in the library — nothing to import")
            continue
        if found.get("alreadyAdded"):
            tally.kept("recipe", f"{name} — the temple already holds it")
            continue
        plan.append((name, found))

    info(f"{len(plan)} to import")
    if args.dry_run:
        for name, found in plan:
            info(f"would import {name} ({found.get('state')})")
        tally.report()
        return 0

    step("importing, answering every close match with what is already in the catalogue")
    reused_total = 0
    for name, found in plan:
        master_id = found["id"]

        # Ask first. The import refuses with KMS-400156 if a close match is left unanswered, and
        # the list is only knowable before the import runs.
        try:
            close = admin.get(f"/api/v1/recipes/import/{master_id}/close-matches")
        except ApiError as e:
            tally.problem(f"could not read close matches for {name}: {e}")
            continue

        decisions = []
        for match in close or []:
            # Always reuse. Phase 01 seeded the catalogue this is matching against, aliases and
            # all, so the thing it found is the thing the recipe means.
            decisions.append({
                "libraryName": match["libraryName"],
                "useIngredientId": match["existingIngredientId"],
                "confirmDifferent": False,
            })
            if args.verbose:
                note(f"  {name}: '{match['libraryName']}' -> {match['existingIngredientName']}")

        payload = {"decisions": decisions} if decisions else None
        try:
            made = admin.post(f"/api/v1/recipes/import/{master_id}", payload)
            args.state.put(f"{PHASE}.recipe.{name}", made["id"])
            reused_total += len(decisions)
            created = made.get("ingredientsCreated", 0)
            detail = f"{made['name']}"
            if decisions:
                detail += f" — {len(decisions)} line(s) matched to the catalogue"
            if created:
                detail += f", {created} new ingredient(s)"
            tally.made("recipe", detail)
        except ApiError as e:
            if e.code in ("KMS-400103", "KMS-400036"):
                tally.kept("recipe", f"{name} — already in the temple")
                continue
            tally.problem(f"importing {name}: {e}")

    # ---- what the import did to the catalogue -----------------------------
    step("what the temple holds now")
    recipes = admin.get("/api/v1/recipes")
    ingredients = admin.get("/api/v1/ingredients")
    derived = admin.get("/api/v1/ingredients/library-derived-count")
    info(f"{len(recipes)} recipe(s), {len(ingredients)} ingredient(s)")
    info(f"{reused_total} recipe line(s) matched to an ingredient that already existed")

    new_count = derived.get("count", 0) if isinstance(derived, dict) else 0
    if new_count:
        fresh = [i["name"] for i in ingredients if i.get("libraryDerived")]
        note(f"{new_count} ingredient(s) were created by the import and have no price or vendor: "
             f"{', '.join(fresh[:10])}" + (" …" if len(fresh) > 10 else ""))
        note("run phase 03 again to give them one")

    # A recipe with no lines would import silently and cook nothing.
    empty = [r["name"] for r in recipes if not r.get("baseYieldQty")]
    if empty:
        tally.problem(f"{len(empty)} recipe(s) came in with no yield: {', '.join(empty[:5])}")

    # ---- the check that has to be here, because nothing else makes it ------
    #
    # The import will happily put a recipe line in one unit family against an ingredient
    # catalogued in another — 1 KG of a Coconut counted in PIECES — and nothing refuses it. It
    # surfaces much later and much worse: the shopping list converts the line to the ingredient's
    # base unit and asks the temple to buy **150,050 coconuts**.
    #
    # It is not a hypothetical. It is what this phase produced the first time it ran, because the
    # vendored library measures coconut by weight while the curated catalogue counts it in pieces.
    # So the mismatch is checked here, where the import that caused it can still be seen.
    step("units the recipes disagree with the catalogue about")
    family = {"KG": "mass", "GM": "mass", "L": "volume", "ML": "volume", "PIECES": "count"}
    catalogued = {i["id"]: i for i in ingredients}
    clashes: dict[str, set] = {}
    checked = 0
    unknown_ingredient = 0
    unknown_unit: set = set()

    for recipe in recipes:
        detail = admin.get(f"/api/v1/recipes/{recipe['id']}")
        for line in detail.get("ingredients") or []:
            ingredient = catalogued.get(line["ingredientId"])
            if not ingredient:
                # A line whose ingredient is not in the catalogue read cannot be compared. It is
                # counted rather than skipped in silence: a guard that quietly declines to look at
                # half its input and then says "none" is worse than no guard.
                unknown_ingredient += 1
                continue
            line_family = family.get(line["unit"])
            ingredient_family = family.get(ingredient["unit"])
            if line_family is None or ingredient_family is None:
                unknown_unit.add(line["unit"] if line_family is None else ingredient["unit"])
                continue
            checked += 1
            if line_family != ingredient_family:
                clashes.setdefault(
                    f"{ingredient['name']} is catalogued in {ingredient['unit']} "
                    f"but recipes measure it in {line['unit']}", set()).add(recipe["name"])

    # "None" only means something alongside how much was looked at. Reported either way, so a
    # clean run is evidence rather than silence — and so a run that checked nothing is obvious.
    info(f"{checked} ingredient line(s) compared across {len(recipes)} recipe(s)")

    if checked == 0:
        tally.problem(
            "the unit check compared no lines at all, so it proves nothing. Either the recipes "
            "have no ingredients or the catalogue read came back empty.")
    elif not clashes:
        info(f"no mismatch: all {checked} line(s) agree with their ingredient's unit family")
    else:
        for what, where in sorted(clashes.items()):
            tally.problem(f"{what} ({len(where)} recipe(s), e.g. {sorted(where)[0]})")
        note("a line in the wrong unit family is not refused by the application, and the "
             "shopping list turns it into a nonsense quantity — fix the catalogue or the recipe "
             "before trusting phase 06's numbers")

    if unknown_ingredient:
        tally.problem(f"{unknown_ingredient} recipe line(s) name an ingredient that is not in the "
                      f"catalogue read, so their units were never compared")
    if unknown_unit:
        tally.problem(f"unit(s) the check does not know: {', '.join(sorted(unknown_unit))}. "
                      f"It only knows KG, GM, L, ML and PIECES; anything else went unchecked.")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
