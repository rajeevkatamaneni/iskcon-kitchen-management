#!/usr/bin/env python3
"""
Phase 01b — give every counted thing a whole reorder level.

"Tell me when agarbatti drops below 7.5" is not a sentence a storekeeper would write, and since the
whole-counts rule shipped the application will not save that row at all: the number has to be fixed
by hand before anything else on the item can be changed. The seeding put those numbers there, by
taking a quarter of the opening count and rounding to one decimal.

**Rounded up, not to nearest.** A reorder level is a warning. One that arrives a packet early is
useful; one that arrives a packet late is the thing it exists to prevent. So 7.5 becomes 8, and 0.2
becomes 1 rather than 0 — a level of zero is not a warning at all.

Only items counted in pieces are touched. A kilogram and a litre are genuinely divisible and 7.5 kg
of rice is a real quantity.

The rule itself lives in `phase01_opening_stock.reorder_threshold` and `01a-resize-opening-stock.tidy`,
both fixed, so a temple seeded from scratch never gets a fractional one. This script is for the
temples seeded before that.

Re-runnable: an item already whole is counted and skipped.

    python3 tools/seed/01b-whole-reorder-levels.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "01b"


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("01b — whole reorder levels on counted things")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    step("the counted items and what they are set to")
    items = admin.get("/api/v1/inventory/items")
    counted = [i for i in items if (i.get("unit") or "").upper() == "PIECES"]
    info(f"{len(items)} item(s) tracked, {len(counted)} of them counted in pieces")

    changed = 0
    for item in sorted(counted, key=lambda i: i.get("ingredientName") or ""):
        current = item.get("reorderThreshold")
        if current is None:
            tally.skip("counted item with no reorder level set")
            continue
        current = float(current)
        wanted = float(math.ceil(current))
        if wanted == current:
            tally.kept("counted item already whole")
            continue
        name = item.get("ingredientName") or "?"
        if args.dry_run:
            info(f"{name:24} {current:g} would become {wanted:g}")
            continue
        try:
            admin.put(f"/api/v1/inventory/items/{item['itemId']}", {
                "storageLocation": item.get("storageLocation"),
                "reorderThreshold": wanted,
                "notes": item.get("notes"),
            })
        except ApiError as e:
            tally.problem(f"{name}: {e}")
            continue
        changed += 1
        tally.made("reorder level rounded up", f"{name:24} {current:g} -> {wanted:g}")

    info(f"{changed} rounded up")
    return tally.report()


if __name__ == "__main__":
    raise SystemExit(main())
