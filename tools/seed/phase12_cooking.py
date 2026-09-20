#!/usr/bin/env python3
"""
Phase 12 — what was actually cooked.

Rajeev's brief: "we also have to simulate day-to-day operations of the temple where meals are
getting prepared. Most days whatever is on the work order gets created however, there might be
days when more or less might be cooked similar that. Also, we have to simulate the recording of
actual data. We have to simulate just enough food leftover food, not enough food all kinds of
combinations."

**Only the past is cooked.** A meal in the future is a plan, not a record, so this stops at
yesterday. The last week of the window stays planned, which is what a temple looks like on any
given morning: a month behind it and a week in front.

**Leftovers and shortfalls are not fields.** The record takes two numbers per dish —
`actualServings`, what was *cooked*, and `consumedQuantity`, what actually *went out*. Everything
else is arithmetic:

    cooked > planned      more was made than the work order said
    cooked < planned      less was made
    consumed < cooked     there was food left over
    consumed == cooked    it all went, and possibly there was not enough
    notMade: true         the dish never happened; it draws no stock at all

So the interesting cases are combinations of those two numbers, and this phase walks through them
on a fixed rotation so every one of them appears somewhere a person can find it.

**Recording draws the stock.** That is the point of it, and it is why this phase runs after the
deliveries rather than before: the drawdown is never refused, but a shortfall is booked as a
`USED_BEYOND_RECORDED_STOCK` movement, which is the honest record of a kitchen that cooked
something it had not accounted for.

    python3 tools/seed/phase12_cooking.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN, kitchen_manager  # noqa: E402

PHASE = "phase12"

# How each day turns out, in rotation. (cooked vs planned, consumed vs cooked, a dish not made)
#
# Weighted towards "as planned" on purpose: Rajeev's words were "most days whatever is on the work
# order gets created", and a simulation where every day is unusual is as unrealistic as one where
# no day is.
DAYS = [
    ("as planned", 1.00, 1.00, False),
    ("as planned", 1.00, 1.00, False),
    ("a little left over", 1.00, 0.88, False),
    ("as planned", 1.00, 1.00, False),
    ("more cooked than planned", 1.15, 1.00, False),
    ("as planned", 1.00, 1.00, False),
    ("a lot left over", 1.05, 0.72, False),
    ("as planned", 1.00, 1.00, False),
    ("short — less cooked than planned", 0.82, 1.00, False),
    ("as planned", 1.00, 1.00, False),
    ("one dish never made", 1.00, 0.95, True),
    ("as planned", 1.00, 1.00, False),
]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 12 — cooking and actuals")

    planner = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False, needs_planner=True), args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    today = date.today()
    meals = planner.get(f"/api/v1/meals?from={args.window_from}&to={args.window_to}")
    if isinstance(meals, dict):
        meals = meals.get("meals", [])

    past = [m for m in meals if m["planDate"] < today.isoformat()]
    future = [m for m in meals if m["planDate"] >= today.isoformat()]
    info(f"{len(past)} meal(s) in the past to record, {len(future)} still ahead and left planned")

    if args.dry_run:
        for index, meal in enumerate(sorted(past, key=lambda m: m["planDate"])[:12]):
            label = DAYS[index % len(DAYS)][0]
            info(f"would record {meal['planDate']} {meal['mealKind']:15} — {label}")
        tally.report()
        return 0

    step("recording what was cooked")
    outcomes: dict[str, int] = {}
    for index, meal in enumerate(sorted(past, key=lambda m: (m["planDate"], m["mealKind"]))):
        key = f"{PHASE}.recorded.{meal['mealId']}"
        if args.state.has(key):
            tally.kept("recorded meal")
            continue

        detail = planner.get(f"/api/v1/meals/{meal['mealId']}")
        # A meal already recorded is left alone; recording twice is refused and correcting is a
        # different act with its own permission.
        if detail.get("recordedAt"):
            args.state.put(key, True)
            tally.kept("recorded meal", f"{meal['planDate']} {meal['mealKind']}")
            continue

        dishes = [d for d in (detail.get("dishes") or [])
                  if (d.get("status") or "PLANNED") == "PLANNED"]
        if not dishes:
            tally.skip("recorded meal", f"{meal['planDate']} {meal['mealKind']} has no dish left")
            continue

        label, cooked_share, eaten_share, skip_one = DAYS[index % len(DAYS)]

        lines = []
        for position, dish in enumerate(dishes):
            dish_id = dish.get("dishId") or dish.get("id")
            planned = float(dish.get("targetYield") or 0)

            # Every still-planned dish has to be named, or the record is refused.
            if skip_one and position == len(dishes) - 1 and len(dishes) > 1:
                lines.append({"dishId": dish_id, "notMade": True})
                continue

            cooked = round(max(planned * cooked_share, 0.1), 2)
            eaten = round(min(cooked * eaten_share, cooked), 2)
            lines.append({
                "dishId": dish_id,
                "actualServings": cooked,
                "consumedQuantity": eaten,
                "notMade": False,
            })

        note_text = {
            "as planned": "Cooked as the work order said.",
            "a little left over": "A little left at the end; sent to the ashram.",
            "a lot left over": "Far fewer people than expected — a lot went back.",
            "more cooked than planned": "More devotees than expected, so an extra batch went on.",
            "short — less cooked than planned": "Gas ran out mid-morning; less was made.",
            "one dish never made": "The sweet was not made — nobody was free to do it.",
        }[label]

        try:
            planner.post(f"/api/v1/meals/{meal['mealId']}/record",
                         {"note": note_text, "dishes": lines})
            args.state.put(key, True)
            outcomes[label] = outcomes.get(label, 0) + 1
            tally.made("recorded meal",
                       f"{meal['planDate']} {meal['mealKind']:15} {len(lines)} dish(es) — {label}")
        except ApiError as e:
            tally.problem(f"{meal['planDate']} {meal['mealKind']}: {e}")

    # ---- one correction ----------------------------------------------------
    # A meal can be corrected once, and it is a different permission — the Temple Admin's, not the
    # planner's. Worth having one on the system: it is the only way the "what it was first"
    # figures ever appear on a screen.
    step("correcting one")
    corrected = f"{PHASE}.corrected"
    if args.state.has(corrected):
        tally.kept("correction")
    else:
        done = [m for m in past if args.state.has(f"{PHASE}.recorded.{m['mealId']}")]
        target = next((m for m in sorted(done, key=lambda m: m["planDate"]) if m), None)
        if not target:
            tally.skip("correction", "nothing recorded to correct")
        else:
            detail = admin.get(f"/api/v1/meals/{target['mealId']}")
            lines = []
            for dish in detail.get("dishes") or []:
                dish_id = dish.get("dishId") or dish.get("id")
                was = dish.get("actualServings")
                if was is None:
                    lines.append({"dishId": dish_id, "notMade": True})
                    continue
                lines.append({
                    "dishId": dish_id,
                    "actualServings": round(float(was) * 1.1, 2),
                    "consumedQuantity": dish.get("consumedQuantity"),
                    "notMade": False,
                })
            try:
                admin.post(f"/api/v1/meals/{target['mealId']}/correct", {
                    "note": "The second batch was left off the sheet when this was written up. "
                            "Figures corrected from the cook's own note.",
                    "dishes": lines,
                })
                args.state.put(corrected, True)
                tally.made("correction",
                           f"{target['planDate']} {target['mealKind']} — a batch that had been "
                           f"left off the sheet")
            except ApiError as e:
                if e.code == "KMS-400137":
                    tally.kept("correction", "already corrected once")
                else:
                    tally.problem(f"correcting {target['planDate']}: {e}")

    # ---- what the kitchen's month looks like -------------------------------
    step("the month as recorded")
    for label, n in sorted(outcomes.items(), key=lambda kv: -kv[1]):
        info(f"  {label:36} {n} meal(s)")

    consumption = admin.get("/api/v1/inventory/movements?type=CONSUMPTION&limit=1000")
    beyond = admin.get("/api/v1/inventory/movements?type=USED_BEYOND_RECORDED_STOCK&limit=1000")
    info(f"{len(consumption)} stock movement(s) from cooking")
    if beyond:
        note(f"{len(beyond)} line(s) were cooked beyond what the store had recorded — the "
             f"application books these rather than refusing the record, which is the honest "
             f"answer when a kitchen has already cooked the food")

    low = admin.get("/api/v1/inventory/items/low-stock")
    if low:
        info(f"{len(low)} ingredient(s) now below their reorder threshold: "
             f"{', '.join(i['ingredientName'] for i in low[:8])}"
             + (" …" if len(low) > 8 else ""))

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
