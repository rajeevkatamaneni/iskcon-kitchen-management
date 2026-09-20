#!/usr/bin/env python3
"""
Phase 01c — bring a counted thing's stock back to a whole number, by recounting it.

Staging holds 400.98 coconuts and 1,080.74 lemons. The application cannot produce those figures any
more — the whole-counts rule shipped as T-423 — but the rows that made them are still on the books,
and nothing corrects them. `IngredientUnits` says as much itself: *"Rows already written stand …
what they cost is that the fraction can no longer be sent back or billed on its own — the remainder
comes off through StockMovementService.compensate, which reverses a movement past validation on
purpose."* This is that sentence carried out.

## Why not the adjustment endpoint, which exists for exactly this

`POST /api/v1/inventory/items/{id}/adjustments` with `reason: COUNT_CORRECTION` is the storekeeper's
"I went and counted it" door, and it cannot be used here. The request is a **signed change**, not a
target, and `InventoryItemService.adjustStock` asks the whole-counts rule of that change:

    IngredientUnits.requireWhole(item.ingredientName(), request.quantity(), unit);

Taking 400.98 down to 400 means posting -0.98, which is refused with `KMS-400191`. Posting -1 leaves
399.98. There is no route that sets a count to N. So the rule is checked on the delta and the one
figure that would fix the balance is the one it will not accept. (This is worth knowing rather than
worth changing: the rule catches a person typing half a coconut, which is what it is for. The narrow
gap is a repair of old rows, which is what this script is for.)

`POST /api/v1/inventory/movements/{id}/compensate` is the way through. It appends a movement's exact
reverse, cross-referencing the original, and goes straight to the insert past validation — on
purpose, for this case, and `StockMovementService.compensate` says so in its own comment. It is the
application's own "that row was a mistake" door, it is audited, and it is behind the same
`MANAGE_INVENTORY` permission.

## What the repair does, per batch

1. Read the item's on hand. Whole already? Counted and skipped, and nothing is called.
2. Read its ledger, and take the movements still standing — not corrections themselves, and not ones
   somebody has already corrected. Group them by batch, because an adjustment is batch-scoped and a
   repair that ignored batches would leave lot balances that no longer describe any lot.
3. In each batch whose balance is fractional, find the fractional rows **somebody typed** and reverse
   them through `compensate`.
4. Whatever that over-removes comes back as one whole `COUNT_CORRECTION` adjustment in the same
   batch, so the batch lands on a whole number and the lot keeps its stock.

Every quantity this script sends is a whole number. It has to be: the same rule that refuses -0.98
would refuse anything else.

## Typed figures may be reversed. Figures the application worked out may not.

`StockMovementService.ENTERED_BY_A_PERSON` is the distinction, and it is the backend's own, not one
invented here: `PO_RECEIPT`, `DONATION_IN_KIND`, `RETURN_TO_VENDOR` and `ADJUSTMENT` carry a number
off a form, and a fraction in one of them is a mis-entry the rule would refuse today. `CONSUMPTION`
and `ISSUE` carry a number the application worked out — a recipe scaled to 12 L genuinely needs 2.4
coconuts, and drawing 5 aprons from lots holding 2.5 and 86 genuinely takes 2.5 from the first.
Reversing one of those would say the cooking did not happen, which is false, and would put back food
that has been eaten.

So where a batch's fraction is the application's own arithmetic, **this script writes nothing and
says so**. That item needs a decision, not a repair, and the decision is Rajeev's. It is reported as
a problem so the run ends non-zero rather than quietly leaving the item as it found it.

`USED_BEYOND_RECORDED_STOCK` is ignored throughout: it moves no stock at all (`to_on_hand_qty`
counts it as zero), so a fractional one cannot be why a balance is fractional.

## Rounded down. 400.98 becomes 400, never 401.

`01b-whole-reorder-levels.py` rounds **up** and this rounds **down**, and the two agree rather than
disagree — both round to the side where being wrong is cheap.

A reorder level is a warning about the future, so early is safe and late is the thing it exists to
prevent. Stock is a claim about the shelf right now, and overstating it is the expensive direction:
the allocator commits food against on hand, so a coconut that does not exist is promised to a meal,
and the kitchen finds out when it is cooking. Understating costs at most one coconut, and it
surfaces as an item reading a little low on the shopping list, which somebody buys.

Round to nearest and 400.98 would become 401 — inventing 0.02 of a coconut to save the temple from
buying one. That is not a recount.

Rounding down is also the only direction that is safe on its own terms: it can only ever take stock
away, so it can never take a batch below zero, and the value rules (R-ING-3) that make a price
mandatory on stock *arriving* are not being worked around. Where the arithmetic does need a positive
leg — reversing a delivery of 160.98 removes all of it, and 160 has to go back — the price comes from
the ingredient's own stock-value suggestion, read before anything is written, and the item is skipped
if there is no rate to be had rather than one being invented.

One consequence, said plainly because it is a real cost: with the residual settled **per batch**, an
item spread over several fractional batches can land up to one unit below the floor of its total —
each batch loses at most its own fraction. Always downwards, never up. The alternative, one residual
for the whole item, keeps that last unit but moves stock between lots that never held it, and a lot
balance nobody can account for is a worse thing to leave behind than a coconut.

## Running it

Re-runnable. An item already whole is counted and skipped and no call is made about it, so a second
run reports everything reused and writes nothing. There is no ledger entry to keep: on hand is the
answer to "has this been done", and the API is asked rather than a file.

    python3 tools/seed/01c-whole-counted-stock.py --api http://localhost:8091 --tenant <id> --dry-run
    python3 tools/seed/01c-whole-counted-stock.py --api http://localhost:8091 --tenant <id>

Run as the Temple Admin, and it signs in as one: the residual is often more than 20% of what the
batch holds, and `ADJUSTMENT_REQUIRES_ADMIN` (`KMS-400025`) refuses a large correction from anybody
who cannot approve one.
"""

from __future__ import annotations

import math
import re
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note, warn  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "01c"

# The movement kinds whose quantity is a figure somebody typed, and therefore the only ones a
# fraction in may honestly be called a mis-entry. Copied from StockMovementService's own
# ENTERED_BY_A_PERSON, which is where the rule lives; see the module docstring for why the other
# kinds are left alone. If that set ever changes, this one follows it.
ENTERED_BY_A_PERSON = {"PO_RECEIPT", "DONATION_IN_KIND", "RETURN_TO_VENDOR", "ADJUSTMENT"}

# Moves no stock at all (T-122), so it can never be why a balance is fractional.
MOVES_NO_STOCK = {"USED_BEYOND_RECORDED_STOCK"}

# What goes in the ledger, so that somebody reading these rows in a year knows what they are.
COMPENSATE_NOTE = ("Recount: a counted thing is held in whole numbers, and this figure was entered "
                   "before that rule. Reversed so the count can be put right (T-366).")
ADJUST_NOTE = ("Recount after reversing a fractional figure: this is what the batch actually holds, "
               "rounded down to a whole number (T-366).")

# The ledger's own cap. Ask for all of it; if an ingredient has more movements than this the totals
# will not reconcile and the item is skipped rather than half-repaired — see reconcile() below.
HISTORY_LIMIT = 1000


def dec(value) -> Decimal:
    """
    A JSON number as an exact decimal.

    `json.loads` gives a float, and 400.98 as a float is 400.97999999999996. Every figure here is a
    `NUMERIC(14,3)` column, so `str()` on the float is the shortest decimal that round-trips and is
    the figure the database actually holds. Doing this arithmetic in floats would make "is it whole"
    a question about binary representation.
    """
    return Decimal(str(value))


def is_whole(value: Decimal) -> bool:
    return value == value.to_integral_value()


def plain(value: Decimal) -> str:
    """A quantity as a person would write it: 400.98, 1080.74, 160."""
    return f"{value.normalize():f}"


def signed(value: Decimal) -> str:
    """A change rather than a quantity, so a line reads as something happening: +160, -88."""
    return ("+" if value > 0 else "") + plain(value)


def counted_units(override: str | None) -> set[str]:
    """
    The units a temple counts one by one — the COUNT family, not the word PIECES.

    `Unit`'s own note asks for this: *"the next counted unit somebody adds — crates, sacks, bundles —
    is one extra word on its own line, and it must inherit this rule by arriving rather than by
    anybody remembering to come back here."* The API exposes no unit metadata at all, so there is
    nothing to ask it; the enum in this same repository is the definition of the rule, and it is read
    rather than copied. Add `CRATES(..., Family.COUNT, ...)` to that file and this script picks it up
    on its next run with no edit here.

    `--counted-units` overrides it for anybody running this from outside a checkout.
    """
    if override:
        return {u.strip().upper() for u in override.split(",") if u.strip()}

    source = (Path(__file__).resolve().parents[2]
              / "backend/src/main/java/org/iskcon/kms/ingredient/Unit.java")
    if not source.exists():
        raise SystemExit(
            f"Cannot read the unit vocabulary at {source}.\n"
            "Run this from a checkout, or name the counted units yourself, e.g. "
            "--counted-units PIECES")

    found = set(re.findall(r"^\s*([A-Z][A-Z_]*)\s*\([^)]*Family\.COUNT", source.read_text(),
                           re.MULTILINE))
    if not found:
        raise SystemExit(f"No COUNT-family unit found in {source} — has Unit.Family been renamed?")
    return found


class Batch:
    """One lot's standing movements, and what can be done about a fraction in them."""

    def __init__(self, batch_id: str) -> None:
        self.batch_id = batch_id
        self.balance = Decimal(0)
        self.entered_fractions: list[dict] = []
        self.derived_fractions: list[dict] = []

    def add(self, movement: dict) -> None:
        quantity = dec(movement["quantity"])
        self.balance += quantity
        if is_whole(quantity):
            return
        if movement["type"] in ENTERED_BY_A_PERSON:
            self.entered_fractions.append(movement)
        else:
            self.derived_fractions.append(movement)

    @property
    def after_reversing(self) -> Decimal:
        """What this lot would hold once the typed fractions are reversed."""
        return self.balance - sum((dec(m["quantity"]) for m in self.entered_fractions), Decimal(0))

    @property
    def target(self) -> Decimal:
        """Where a recount says this lot should land: down to the whole number below."""
        return Decimal(math.floor(self.balance))

    @property
    def residual(self) -> Decimal:
        """The whole figure to post after the reversals. Zero means the reversals were enough."""
        return self.target - self.after_reversing


def standing_batches(movements: list[dict]) -> dict[str, Batch]:
    """
    The ledger grouped by lot, counting only the rows still standing.

    Two kinds of row are left out and both matter. A row carrying `CORRECTION` is itself somebody's
    reversal, and compensating one would re-apply the original — putting the mistake back. A row that
    has already been corrected is netted to zero by its correction, so its fraction is not why
    anything is fractional, and `compensate` would refuse it anyway with `KMS-400039`.
    """
    corrected = {m["referenceId"] for m in movements if m.get("referenceType") == "CORRECTION"}

    batches: dict[str, Batch] = {}
    for movement in movements:
        if movement.get("referenceType") == "CORRECTION" or movement["id"] in corrected:
            continue
        if movement["type"] in MOVES_NO_STOCK:
            continue
        batches.setdefault(movement["batchId"], Batch(movement["batchId"])).add(movement)
    return batches


def reconcile(batches: dict[str, Batch], on_hand: Decimal) -> str | None:
    """
    Whether the ledger this script read adds up to the on-hand figure the API reports.

    The guard against repairing something that has not been understood. It catches an ingredient with
    more than `HISTORY_LIMIT` movements (the history is capped, so the tail would be invisible), a
    movement in a second unit that would need converting, and any disagreement with
    `to_on_hand_qty` that this script does not know about. Returns the reason to skip, or None.
    """
    total = sum((b.balance for b in batches.values()), Decimal(0))
    if total != on_hand:
        return (f"the movements read come to {plain(total)} but the API reports {plain(on_hand)} on "
                f"hand; not repairing a ledger this script has not accounted for")
    return None


def plan(batches: dict[str, Batch], item_unit: str) -> tuple[list[Batch], list[str]]:
    """The lots to repair, and the reasons any of them cannot be."""
    todo, blocked = [], []
    for batch in sorted(batches.values(), key=lambda b: b.batch_id):
        if is_whole(batch.balance):
            continue
        odd_unit = {m["unit"] for m in batch.entered_fractions + batch.derived_fractions
                    if m["unit"] != item_unit}
        if odd_unit:
            blocked.append(f"lot {batch.batch_id[:8]} has a fractional movement in "
                           f"{', '.join(sorted(odd_unit))} rather than {item_unit}; this script does "
                           f"not convert between units")
            continue
        if not is_whole(batch.after_reversing):
            which = ", ".join(f"{m['type']} {plain(dec(m['quantity']))}"
                              for m in batch.derived_fractions) or "none found"
            blocked.append(f"lot {batch.batch_id[:8]} holds {plain(batch.balance)} and the fraction "
                           f"is the application's own arithmetic, not a mis-entry ({which}). "
                           f"Reversing that would say the cooking or the issue never happened. "
                           f"Needs a decision, not a repair")
            continue
        todo.append(batch)
    return todo, blocked


def price_for(admin, ingredient_id: str) -> Decimal | None:
    """
    What the temple would pay for one of these today, for a residual that puts stock back.

    Required and above zero on any adjustment that adds (R-ING-3), and asked before anything is
    written, so an item with no rate is skipped whole rather than left half repaired. The suggestion
    endpoint answers from the preferred vendor's price or the ingredient's own market rate; a script
    correcting a count has no business inventing either.
    """
    try:
        suggestion = admin.get(f"/api/v1/ingredients/{ingredient_id}/stock-value-suggestion")
    except ApiError:
        return None
    value = (suggestion or {}).get("pricePerUnit")
    if value is None or dec(value) <= 0:
        return None
    return dec(value)


def repair(admin, item: dict, batch: Batch, price: Decimal | None, tally: Tally) -> None:
    """Reverse the typed fractions in one lot, then post what the recount says it holds."""
    name = item.get("ingredientName") or "?"
    for movement in batch.entered_fractions:
        admin.post(f"/api/v1/inventory/movements/{movement['id']}/compensate",
                   {"note": COMPENSATE_NOTE})
        tally.made("fractional movement reversed",
                   f"{name:24} lot {batch.batch_id[:8]} {movement['type']} "
                   f"{plain(dec(movement['quantity']))}")

    if batch.residual == 0:
        return

    body = {
        "batchId": batch.batch_id,
        # Always a whole number — the same rule that refuses -0.98 would refuse anything else.
        "quantity": int(batch.residual),
        "unit": item["unit"],
        "reason": "COUNT_CORRECTION",
        "note": ADJUST_NOTE,
    }
    if batch.residual > 0:
        body["pricePerUnit"] = float(price)
    admin.post(f"/api/v1/inventory/items/{item['itemId']}/adjustments", body)
    tally.made("recount posted",
               f"{name:24} lot {batch.batch_id[:8]} {signed(batch.residual):>6}, "
               f"so the lot holds {plain(batch.target)}")


def extra_flags(parser) -> None:
    parser.add_argument("--counted-units", default=None,
                        help="the units to treat as counted, comma separated; read from the "
                             "backend's own Unit enum when not given")


def main() -> int:
    args = parse_args(PHASE, extra_flags)
    tally = Tally("01c — whole stock on counted things")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    units = counted_units(getattr(args.raw, "counted_units", None))
    step(f"the counted items and what they hold ({', '.join(sorted(units))})")
    items = admin.get("/api/v1/inventory/items")
    counted = [i for i in items if (i.get("unit") or "").upper() in units]
    info(f"{len(items)} item(s) tracked, {len(counted)} of them counted one by one")

    for item in sorted(counted, key=lambda i: i.get("ingredientName") or ""):
        name = item.get("ingredientName") or "?"
        on_hand = dec(item.get("onHand") or 0)
        if is_whole(on_hand):
            tally.kept("counted item already whole")
            continue

        info(f"{name:24} holds {plain(on_hand)} {item['unit']}")
        movements = admin.get(f"/api/v1/inventory/movements"
                              f"?ingredientId={item['ingredientId']}&limit={HISTORY_LIMIT}")
        batches = standing_batches(movements)

        mismatch = reconcile(batches, on_hand)
        if mismatch:
            tally.problem(f"{name}: {mismatch}")
            continue

        todo, blocked = plan(batches, item["unit"])
        for reason in blocked:
            tally.problem(f"{name}: {reason}")
        if blocked:
            # All or nothing. A part-repaired item is still fractional, and the rows written towards
            # it would have to be read and understood by whoever picks the rest up.
            note(f"{name}: left exactly as found")
            continue

        price = None
        putting_back = sum((b.residual for b in todo if b.residual > 0), Decimal(0))
        if putting_back > 0:
            price = price_for(admin, item["ingredientId"])
            if price is None:
                tally.problem(f"{name}: putting {plain(putting_back)} back needs a price per "
                              f"{item['unit']}, and the temple has no rate or vendor price for it. "
                              f"Set one, then run this again")
                continue

        target = sum((b.target for b in todo), Decimal(0)) + sum(
            (b.balance for b in batches.values() if is_whole(b.balance)), Decimal(0))

        if args.dry_run:
            for batch in todo:
                reversals = ", ".join(f"{m['type']} {plain(dec(m['quantity']))}"
                                      for m in batch.entered_fractions)
                info(f"  lot {batch.batch_id[:8]} {plain(batch.balance):>10} -> "
                     f"{plain(batch.target):>8}  reverse [{reversals}]"
                     + (f", then post {signed(batch.residual)}" if batch.residual else ""))
            info(f"  {name} would go {plain(on_hand)} -> {plain(target)}"
                 + (f" at Rs {plain(price)} per {item['unit']}" if price else ""))
            tally.skip("would be repaired")
            continue

        try:
            for batch in todo:
                repair(admin, item, batch, price, tally)
        except ApiError as e:
            tally.problem(f"{name}: {e}")
            continue

        # Read back rather than assume. The ledger is what decides whether this worked, and the only
        # honest way to know is to ask it again.
        after = dec(admin.get(f"/api/v1/inventory/items/{item['itemId']}")["item"].get("onHand") or 0)
        if not is_whole(after):
            tally.problem(f"{name}: still holds {plain(after)} after the repair")
        elif after != target:
            warn(f"{name}: landed on {plain(after)}, not the {plain(target)} planned")
        info(f"{name:24} {plain(on_hand)} -> {plain(after)}")

    return tally.report()


if __name__ == "__main__":
    raise SystemExit(main())
