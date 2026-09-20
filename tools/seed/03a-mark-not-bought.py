#!/usr/bin/env python3
"""
Phase 03a — put the "never bought" mark back on the ingredients that carry it in the library.

**Why this script exists.** A curated library recipe can mark an ingredient as one the temple never
buys — water is the case everybody recognises. The mark rides on the library recipe. But a temple's
own `ingredients` row is created by phase 03 from the *names* a recipe uses, and creating an
ingredient does not consult the library row that named it, so the mark does not follow. The result
is a shopping list that asks a temple to go out and buy water.

This is a gap in the application, not in the seeding: two places know about an ingredient and only
one of them knows this fact about it. Until they are joined up, this script closes it from outside,
the way a Temple Admin would — `PATCH /api/v1/ingredients/{id}/not-bought`, which is a Temple Admin
act (`KMS-400021` for anyone else) and is deliberately kept off `PUT` so a rename cannot clear it.

It reads the library first and only marks what the library actually marks, so it cannot invent the
fact. If the library turns out not to carry the mark either, it says so and changes nothing — that
would be a different and worse bug, and quietly setting the flag would hide it.

Re-runnable: an ingredient already marked is left alone and counted as such.

    python3 tools/seed/03a-mark-not-bought.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "03a"

# The prefix the curated books were loaded under. Only these are consulted: the 5,000-odd rows of
# the old import are being retired and are not evidence of anything.
CURATED = "tools/seed/02b-build-catalogue.mjs"


CATALOGUE = Path(__file__).resolve().parent / "data" / "catalogue.json"


def candidates_from_the_catalogue() -> set[str]:
    """
    The names the curated books mark, read from the file that was loaded.

    This is the *question*, not the answer. The file says which names are worth asking the library
    about; the library then has to confirm each one before anything is marked. Reading the file
    alone would mean trusting a copy of the data over the data, and would mark water even if the
    loader had thrown the flag away — which is the bug this script has to be able to detect.
    """
    found: set[str] = set()

    def walk(node) -> None:
        if isinstance(node, dict):
            if node.get("not_bought") or node.get("notBought"):
                name = (node.get("name") or "").strip()
                if name:
                    found.add(name)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(json.loads(CATALOGUE.read_text()))
    return found


def library_not_bought(admin, candidates: set[str]) -> tuple[set[str], int, int]:
    """
    Ask the library about each candidate name and keep only what it confirms.

    The library is searched by name rather than paged end to end: the old import left thousands of
    rows behind and reading every one of them takes over an hour for an answer that concerns a
    handful of ingredients.
    """
    confirmed: set[str] = set()
    read = 0
    marked_rows = 0
    seen: set[str] = set()
    for candidate in sorted(candidates):
        for row in admin.get(f"/api/v1/library/recipes?q={quote(candidate)}&limit=100") or []:
            rid = row.get("id")
            if not rid or rid in seen:
                continue
            seen.add(rid)
            try:
                detail = admin.get(f"/api/v1/library/recipes/{rid}")
            except ApiError as e:
                # KMS-400105: the row went between the search and the read. The old import is
                # being retired in the background and a search result can name a recipe that no
                # longer exists a second later. A curated row is never one of them.
                if e.status == 404:
                    continue
                raise
            if not (detail.get("sourceRef") or "").startswith(CURATED):
                continue
            read += 1
            hit = False
            for ing in detail.get("ingredients") or []:
                if ing.get("notBought") or ing.get("not_bought"):
                    name = (ing.get("name") or "").strip()
                    if name:
                        confirmed.add(name.casefold())
                        hit = True
            if hit:
                marked_rows += 1
    return confirmed, read, marked_rows


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("03a — the never-bought mark")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    step("reading the curated library for the never-bought mark")
    candidates = candidates_from_the_catalogue()
    info(f"the curated books ask about {len(candidates)}: "
         + (", ".join(sorted(candidates)) if candidates else "nothing"))
    wanted, read, marked_rows = library_not_bought(admin, candidates)
    info(f"{read} curated recipes read; {marked_rows} of them mark at least one ingredient")
    info(f"{len(wanted)} distinct ingredient name(s) marked: "
         + (", ".join(sorted(wanted)) if wanted else "none"))

    if not wanted:
        tally.problem(
            "The curated library does not mark anything as never bought. Nothing to copy across, "
            "and this script will not invent the fact. If water is on the shopping list, the mark "
            "was lost on the way into the library and that is where to look.")
        tally.report()
        return 2

    step("marking the temple's own ingredients")
    rows = admin.get("/api/v1/ingredients")
    rows = rows.get("content") if isinstance(rows, dict) else rows
    already = changed = 0
    for row in rows or []:
        if (row.get("name") or "").strip().casefold() not in wanted:
            continue
        if row.get("notBought"):
            already += 1
            tally.kept("ingredient already marked never bought")
            continue
        if args.dry_run:
            info(f"{row['name']}: would be marked")
            continue
        try:
            admin.patch(f"/api/v1/ingredients/{row['id']}/not-bought", {"notBought": True})
        except ApiError as e:
            tally.problem(f"{row['name']}: refused — {e}")
            continue
        changed += 1
        tally.made("ingredient marked never bought")
        info(f"{row['name']}: marked, and it will not appear on a shopping list again")
    info(f"{changed} marked now, {already} already marked")

    return tally.report()


if __name__ == "__main__":
    raise SystemExit(main())
