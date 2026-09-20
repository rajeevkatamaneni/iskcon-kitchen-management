#!/usr/bin/env python3
"""
Phase 07 — the deliveries.

Rajeev's brief: "Deliveries will be partial, full delivery at once, late deliveries and a
combination of all the delivery types. Also reject some items upon delivery because the qualities
is not good and also do some returns after the fact."

So each sent order is given a different kind of delivery, and between them they cover every case:

    full and on time        everything arrives, nothing wrong with it
    partial, then the rest  two receipts against one order, days apart
    late                    arrives after the day it was needed
    rejected on the gate    some of it is spoiled and never enters stock
    returned afterwards     accepted, then sent back days later
    still short             a partial delivery nobody has chased, left open

**Rejected is not the same as returned, and the difference is where the stock goes.** A rejection
happens at the gate: the quantity never enters stock at all. A return happens afterwards: the
stock came in, was counted, and then went back out as a negative movement. A simulation that only
did one of them would leave half the ledger untested.

**Every receipt carries an idempotency key derived from the order and the part**, so running this
twice returns the first receipt instead of inventing a second. That is the application's own
protection and it is the thing that makes this phase re-runnable at all — the endpoint has no
other notion of "already done".

    python3 tools/seed/phase07_deliveries.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import KITCHEN_STAFF, TEMPLE_ADMIN, kitchen_manager  # noqa: E402

PHASE = "phase07"

# What happens to each order. Chosen from the order's OWN NUMBER, not from its position in this
# run's list, and that distinction is the whole reason the variety exists.
#
# Keyed on position, each ordering round starts counting again. On staging a round only ever had
# three orders out with vendors, so the index never got past 2 and **rejected, returned and
# still-short never happened at all** — four rounds produced nine orders, every one of them
# received in full or in two parts, and none of the cases Rajeev asked for by name. Keyed on the
# PO number, PO-2026-0004 is the rejected one wherever and whenever it is delivered.
SCRIPTS = ["full", "partial_then_rest", "late", "rejected", "full_then_return", "still_short"]


def part(quantity: float, share: float, unit: str) -> float:
    """
    A share of a delivered quantity, in a number that can actually turn up.

    Sixty per cent of sixteen aprons is 9.6 aprons, and the application will happily record it —
    it only knows the unit is PIECES, not that a piece is indivisible. A return of "1.5 Pieces of
    Apron" is the sort of line that makes a reader distrust everything else on the screen, so a
    count is rounded to a whole one here, and never to zero when something was meant to move.
    """
    value = quantity * share
    if unit == "PIECES":
        value = max(1.0, round(value)) if quantity >= 1 else quantity
        return float(min(value, quantity))
    return round(value, 3)


def script_for(po_number: str) -> str:
    """The delivery this order gets, from its own sequence number."""
    digits = "".join(c for c in po_number.split("-")[-1] if c.isdigit())
    n = int(digits) if digits else 1
    return SCRIPTS[(n - 1) % len(SCRIPTS)]


def receipt(session, po_id: str, key: str, lines: list, *, when: date | None = None,
            note_text: str | None = None, ref: str | None = None) -> dict:
    payload = {
        "idempotencyKey": key,
        "deliveryNoteRef": ref,
        "note": note_text,
        "lines": lines,
    }
    if when:
        for line in payload["lines"]:
            line.setdefault("receivedDate", when.isoformat())
    return session.post(f"/api/v1/purchase-orders/{po_id}/receipts", payload)


def extra_args(parser) -> None:
    parser.add_argument("--round", type=int, default=1, dest="round_no",
                        help="which ordering round these deliveries belong to")
    parser.add_argument("--only", default=None,
                        help="deliver just this purchase order, by its number")
    parser.add_argument("--as", default=None, dest="as_script", choices=SCRIPTS,
                        help="deliver it as this case instead of the one its number implies. "
                             "For filling a gap: the mapping spreads six cases across the order "
                             "book, so a case can go unrepresented if the book stops short of it.")


def main() -> int:
    args = parse_args(PHASE, extra_args)
    round_no = args.raw.round_no
    tally = Tally(f"phase 07 — deliveries (round {round_no})")

    # Receiving is the store's job, not the manager's. KITCHEN_STAFF holds RECEIVE_DELIVERIES.
    store = sign_in(args.api, KITCHEN_STAFF[0], args.tenant)
    manager = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False), args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    orders = [p for p in manager.get("/api/v1/purchase-orders")
              if p["status"] in ("SENT", "PARTIALLY_RECEIVED")]
    if args.raw.only:
        orders = [p for p in orders if p["poNumber"] == args.raw.only]
        if not orders:
            tally.problem(f"{args.raw.only} is not out with a vendor, so nothing can be "
                          f"delivered against it")
            return tally.report()
    orders.sort(key=lambda p: p["poNumber"])
    if not orders:
        tally.problem("no sent orders to deliver against — run phase 06 first")
        return tally.report()

    info(f"{len(orders)} order(s) out with vendors")
    today = date.today()

    for order in orders:
        po_id, po_number = order["id"], order["poNumber"]
        script = args.raw.as_script or script_for(po_number)
        detail = manager.get(f"/api/v1/purchase-orders/{po_id}")

        # Only catalogue lines can be received; a described line "arrives" instead.
        lines = [l for l in detail["lines"] if l.get("ingredientId")]
        if not lines:
            tally.skip("delivery", f"{po_number} has no catalogue lines")
            continue

        step(f"{po_number} — {order['vendorName']} — {script.replace('_', ' ')}")

        try:
            if script == "full":
                key = f"{PHASE}.r{round_no}.{po_number}.full"
                if args.state.has(key):
                    tally.kept("delivery", po_number)
                    continue
                made = receipt(store, po_id, key, [
                    {"poLineId": l["id"], "receivedQty": float(l["quantity"]), "rejectedQty": 0}
                    for l in lines
                ], when=today - timedelta(days=2), ref=f"DN/{po_number[-4:]}/A",
                    note_text="Everything arrived together and in good order.")
                args.state.put(key, made["id"])
                tally.made("delivery", f"{po_number} in full, {len(lines)} line(s)")

            elif script == "partial_then_rest":
                first = f"{PHASE}.r{round_no}.{po_number}.part1"
                if not args.state.has(first):
                    made = receipt(store, po_id, first, [
                        {"poLineId": l["id"],
                         "receivedQty": part(float(l["quantity"]), 0.6, l["unit"]),
                         "rejectedQty": 0}
                        for l in lines
                    ], when=today - timedelta(days=5), ref=f"DN/{po_number[-4:]}/A",
                        note_text="Van could not take the whole order; rest to follow.")
                    args.state.put(first, made["id"])
                    tally.made("delivery", f"{po_number} part one — 60% of {len(lines)} line(s)")

                second = f"{PHASE}.r{round_no}.{po_number}.part2"
                if not args.state.has(second):
                    fresh = manager.get(f"/api/v1/purchase-orders/{po_id}")
                    remaining = []
                    for line in fresh["lines"]:
                        if not line.get("ingredientId"):
                            continue
                        had = sum(
                            float(p["receivedQty"])
                            for r in store.get(f"/api/v1/purchase-orders/{po_id}/receipts")
                            for p in r["lines"] if p["poLineId"] == line["id"])
                        left = round(float(line["quantity"]) - had, 3)
                        if left > 0:
                            remaining.append({"poLineId": line["id"], "receivedQty": left,
                                              "rejectedQty": 0})
                    if remaining:
                        made = receipt(store, po_id, second, remaining,
                                       when=today - timedelta(days=3),
                                       ref=f"DN/{po_number[-4:]}/B",
                                       note_text="Balance of the order.")
                        args.state.put(second, made["id"])
                        tally.made("delivery", f"{po_number} part two — the balance")

            elif script == "late":
                key = f"{PHASE}.r{round_no}.{po_number}.late"
                if args.state.has(key):
                    tally.kept("delivery", po_number)
                    continue
                # Arriving late means arriving after the day it was needed, and the note must not
                # claim it if it is not true. Phase 06 had to clamp `neededBy` to tomorrow — the
                # application refuses an order needed before it was raised — so until the
                # back-dating script moves these orders into the past, the best this can do is
                # deliver on the latest day it is allowed to and say so plainly.
                needed = order.get("neededBy")
                arrived = today
                really_late = bool(needed and arrived.isoformat() > needed)
                if really_late:
                    text = (f"Arrived {arrived}, needed by {needed}. Vendor said the lorry broke "
                            f"down outside Hosur.")
                else:
                    text = (f"Arrived {arrived} against a needed-by of {needed}. Recorded on the "
                            f"last day available; this becomes a late delivery once the order "
                            f"dates are moved into the past.")
                made = receipt(store, po_id, key, [
                    {"poLineId": l["id"], "receivedQty": float(l["quantity"]), "rejectedQty": 0}
                    for l in lines
                ], when=arrived, ref=f"DN/{po_number[-4:]}/A", note_text=text)
                args.state.put(key, made["id"])
                tally.made("delivery",
                           f"{po_number} arrived {arrived}, needed {needed}"
                           + ("  — late" if really_late else "  — not yet late, see the note"))

            elif script == "rejected":
                key = f"{PHASE}.r{round_no}.{po_number}.rejected"
                if args.state.has(key):
                    tally.kept("delivery", po_number)
                    continue
                # Which lines come in spoiled. The old rule — every fourth line, if it is
                # bigger than two — matched nothing at all on a short order, and the "rejected"
                # delivery then rejected nothing while still reporting itself as the rejection
                # case. So the lines are chosen first and the order is guaranteed at least one.
                spoiled = {i for i, l in enumerate(lines)
                           if i % 4 == 1 and float(l["quantity"]) > 2}
                if not spoiled:
                    biggest = max(range(len(lines)), key=lambda i: float(lines[i]["quantity"]))
                    if float(lines[biggest]["quantity"]) >= 2:
                        spoiled = {biggest}

                payload_lines = []
                rejected_names = []
                for position, line in enumerate(lines):
                    quantity = float(line["quantity"])
                    if position in spoiled:
                        bad = part(quantity, 0.2, line["unit"])
                        if bad >= quantity:
                            bad = part(quantity, 0.5, line["unit"])
                        payload_lines.append({
                            "poLineId": line["id"],
                            "receivedQty": round(quantity - bad, 3),
                            "rejectedQty": bad,
                            "rejectReason": "SPOILED",
                        })
                        rejected_names.append(
                            f"{line['ingredientName']} {bad:g} {line['unit']}")
                    else:
                        payload_lines.append({"poLineId": line["id"], "receivedQty": quantity,
                                              "rejectedQty": 0})
                if not rejected_names:
                    tally.problem(f"{po_number} was meant to have a rejection and nothing on it "
                                  f"was big enough to reject")
                made = receipt(store, po_id, key, payload_lines,
                               when=today - timedelta(days=1), ref=f"DN/{po_number[-4:]}/A",
                               note_text="Some of it had gone off in the van. Sent back at the gate.")
                args.state.put(key, made["id"])
                tally.made("delivery",
                           f"{po_number} with {len(rejected_names)} line(s) rejected at the gate: "
                           f"{', '.join(rejected_names) or 'none'}")

            elif script == "full_then_return":
                key = f"{PHASE}.r{round_no}.{po_number}.full"
                if not args.state.has(key):
                    made = receipt(store, po_id, key, [
                        {"poLineId": l["id"], "receivedQty": float(l["quantity"]),
                         "rejectedQty": 0}
                        for l in lines
                    ], when=today - timedelta(days=6), ref=f"DN/{po_number[-4:]}/A",
                        note_text="Accepted in full.")
                    args.state.put(key, made["id"])
                    tally.made("delivery", f"{po_number} in full, {len(lines)} line(s)")

                # …and three days later a sack of it turned out to be bad.
                back = f"{PHASE}.r{round_no}.{po_number}.return"
                if not args.state.has(back):
                    receipt_id = args.state.get(key)
                    full = store.get(f"/api/v1/purchase-orders/{po_id}/receipts")
                    this = next((r for r in full if r["id"] == receipt_id), None)
                    target = next((l for l in (this or {}).get("lines", [])
                                   if float(l["receivedQty"]) > 3), None)
                    if not target:
                        tally.skip("return", f"{po_number} has nothing big enough to send back")
                    else:
                        quantity = part(float(target["receivedQty"]), 0.15,
                                        target["unit"])
                        # Returns are MANAGE_INVENTORY, not MANAGE_PURCHASE_ORDERS — the store
                        # decides something has gone off, not the person who ordered it.
                        made = admin.post(f"/api/v1/goods-receipts/{receipt_id}/returns", {
                            "idempotencyKey": back,
                            "receiptLineId": target["id"],
                            "quantity": quantity,
                            "reason": "SPOILED",
                            "note": "Found soft at the bottom of the sack three days after it came in.",
                        })
                        args.state.put(back, made["id"])
                        tally.made("return",
                                   f"{po_number}: {quantity} {target['unit']} of "
                                   f"{target['ingredientName']} sent back")

            elif script == "still_short":
                key = f"{PHASE}.r{round_no}.{po_number}.short"
                if args.state.has(key):
                    tally.kept("delivery", po_number)
                    continue
                made = receipt(store, po_id, key, [
                    {"poLineId": l["id"],
                     "receivedQty": part(float(l["quantity"]), 0.45, l["unit"]),
                     "rejectedQty": 0}
                    for l in lines
                ], when=today - timedelta(days=4), ref=f"DN/{po_number[-4:]}/A",
                    note_text="Less than half came. Vendor has not said when the rest will.")
                args.state.put(key, made["id"])
                tally.made("delivery", f"{po_number} short — 45% delivered, still open")

        except ApiError as e:
            tally.problem(f"{po_number} ({script}): {e}")

    # ---- what the deliveries screen shows ---------------------------------
    step("the deliveries as the application holds them")
    orders = manager.get("/api/v1/purchase-orders")
    by_status: dict[str, int] = {}
    for order in orders:
        by_status[order["status"]] = by_status.get(order["status"], 0) + 1
    for status, n in sorted(by_status.items()):
        info(f"  {status:22} {n}")

    view = store.get("/api/v1/deliveries")
    info(f"{len(view.get('open') or [])} line(s) still to come, "
         f"{len(view.get('received') or [])} receipt(s) recorded")

    movements = admin.get("/api/v1/inventory/movements?type=PO_RECEIPT&limit=500")
    returns = admin.get("/api/v1/inventory/movements?type=RETURN_TO_VENDOR&limit=500")
    info(f"{len(movements)} stock movement(s) from deliveries, {len(returns)} from returns")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
