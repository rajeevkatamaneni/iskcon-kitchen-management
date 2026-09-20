#!/usr/bin/env python3
"""
Phase 06 — the shopping list, then the orders.

Rajeev's brief: "then run our ordering campaign you will start with the shopping list then create
purchase orders then similar deliveries. When you're doing your shopping list be sure to order
supplies as well. Supplies can be gas cylinders, cleaning products, lightbulbs, stools, cups plates
anything the temple needs to run a kitchen operation."

**The list is not something you generate.** It is derived on every `GET /api/v1/shopping-list`
from what the plan needs against what is on the shelf. There is no regenerate endpoint and no
"turn the list into orders" endpoint — both were removed, and tests assert their absence. So this
phase reads the list, decides what to buy, and raises one order per vendor by hand.

**Supplies are added to the list deliberately.** Nothing plans them: no recipe calls for a gas
cylinder, so they never appear on a derived list however low they get. The temple still has to buy
them, so this phase puts them on with `POST /api/v1/shopping-list` the way a Store Manager would.

**Water is taken off the list every time.** Rajeev marked it not-bought in his curated recipes, but
the application has nowhere to keep that, so it has to be excluded list by list with
`PATCH …/{ingredientId}` and `{"included": false}`.
**TODO — delete that step once the `not_bought` flag lands** (a column on `ingredients` with the
shopping list excluding it). Until then, any list built by another route will order water.

    python3 tools/seed/phase06_orders.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN, kitchen_manager  # noqa: E402

PHASE = "phase06"
DATA = Path(__file__).resolve().parent / "data" / "catalogue.json"

# What the temple re-orders that no recipe ever asks for. Quantities a month's cooking uses up.
SUPPLY_ORDER = {
    "LPG cylinder": 12, "Dishwash liquid": 30, "Floor cleaner": 20, "Detergent powder": 20,
    "Bleaching powder": 8, "Hand wash": 10, "Scrubbing pad": 60, "Broom": 6, "Mop": 4,
    "Garbage bag": 600, "Kitchen duster": 30,
    "Leaf plate": 6000, "Paper cup": 4000, "Disposable spoon": 4000, "Tissue roll": 40,
    "Aluminium foil": 6, "Cling film": 4, "Butter paper": 8,
    "LED bulb": 15, "Tube light": 6, "Hand gloves": 300, "Apron": 10, "Head cap": 600,
    "Camphor": 2, "Cotton wick": 3, "Agarbatti": 24, "Matchbox": 60,
}

# Things a temple genuinely does not put on a purchase order.
NEVER_ORDER = {"water"}


def extra_args(parser) -> None:
    parser.add_argument(
        "--round", type=int, default=1, dest="round_no",
        help="which ordering round this is (1, 2, 3 …). A temple buys weekly, not once a month, "
             "and one round against a month of cooking leaves the store empty — measured: 1,574 "
             "lines cooked beyond recorded stock against 681 drawn from it.")


def main() -> int:
    args = parse_args(PHASE, extra_args)
    round_no = args.raw.round_no
    tally = Tally(f"phase 06 — the shopping list and the orders (round {round_no})")

    manager = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False), args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    ingredients = {i["name"]: i for i in admin.get("/api/v1/ingredients")}

    # ---- supplies on to the list ------------------------------------------
    step("putting the supplies on the list")
    for name, quantity in SUPPLY_ORDER.items():
        ingredient = ingredients.get(name)
        if not ingredient:
            tally.skip("supply", f"{name} is not in the catalogue")
            continue
        key = f"{PHASE}.r{round_no}.listline.{name}"
        if args.state.has(key):
            tally.kept("supply on the list", name)
            continue
        try:
            manager.post("/api/v1/shopping-list",
                         {"ingredientId": ingredient["id"], "suggestedQty": quantity})
            args.state.put(key, True)
            tally.made("supply on the list", f"{name} x{quantity}")
        except ApiError as e:
            if e.code == "KMS-400131":
                args.state.put(key, True)
                tally.kept("supply on the list", name)
            else:
                tally.problem(f"{name} on to the list: {e}")

    # ---- what the temple is not buying ------------------------------------
    step("taking off what the temple does not buy")
    lines = manager.get("/api/v1/shopping-list")
    for line in lines:
        if line["ingredientName"].strip().lower() not in NEVER_ORDER:
            continue
        if not line.get("included", True):
            tally.kept("excluded", line["ingredientName"])
            continue
        try:
            manager.patch(f"/api/v1/shopping-list/{line['ingredientId']}",
                          {"suggestedQty": None, "included": False})
            tally.made("excluded", f"{line['ingredientName']} — the temple does not buy it")
        except ApiError as e:
            tally.problem(f"excluding {line['ingredientName']}: {e}")

    # ---- the list as it now stands ----------------------------------------
    step("the list")
    lines = [l for l in manager.get("/api/v1/shopping-list") if l.get("included", True)]
    info(f"{len(lines)} line(s) to buy")

    no_vendor = [l["ingredientName"] for l in lines if not l.get("suggestedVendorId")]
    if no_vendor:
        tally.problem(f"{len(no_vendor)} line(s) have no vendor and cannot be ordered: "
                      f"{', '.join(no_vendor)}")

    by_vendor: dict[str, list] = defaultdict(list)
    for line in lines:
        if not line.get("suggestedVendorId"):
            continue
        by_vendor[line["suggestedVendorId"]].append(line)

    # What this vendor has already been ordered from, read from the application rather than from
    # the ledger. Same reasoning as phase 05: the ledger is a file on one machine, and a purchase
    # order has no natural key, so without this a second run on a machine that never held the
    # ledger raises a duplicate order to every vendor and the temple appears to have bought
    # everything twice. A vendor with a live order is left alone.
    live = {p["vendorId"] for p in manager.get("/api/v1/purchase-orders")
            if p["status"] not in ("CANCELLED",)}
    if live and round_no == 1:
        note(f"{len(live)} vendor(s) already have an order; they will be left alone")
    elif live:
        note(f"round {round_no}: {len(live)} vendor(s) have earlier orders; this round adds to them")

    info(f"{len(by_vendor)} vendor(s) to order from")
    if args.dry_run:
        for vendor_id, rows in by_vendor.items():
            info(f"would order {len(rows)} line(s) from {rows[0]['suggestedVendorName']}")
        tally.report()
        return 0

    # ---- one order per vendor ---------------------------------------------
    step("raising the orders")
    for vendor_id, rows in sorted(by_vendor.items(), key=lambda kv: kv[1][0]["suggestedVendorName"]):
        vendor_name = rows[0]["suggestedVendorName"]
        key = f"{PHASE}.r{round_no}.po.{vendor_name}"
        if args.state.has(key) or (round_no == 1 and vendor_id in live):
            tally.kept("purchase order", vendor_name)
            continue

        # An order cannot be needed before it was raised — KMS-400014, and rightly so. But most of
        # this window is already in the past, and the shopping list works out `neededBy` from the
        # plan, so nearly every line asks for a date behind us. Clamping to tomorrow is what a real
        # temple does when it is late: it orders now, for as soon as the vendor can manage.
        #
        # The historical orders are made to *look* historical afterwards, by the one back-dating
        # script, which is the only thing that can do it — the application has no path for it, on
        # purpose.
        tomorrow = (date.today() + timedelta(days=1)).isoformat()
        needed = [max(l["neededBy"], tomorrow) for l in rows if l.get("neededBy")]
        po_lines = []
        for line in rows:
            entry = {
                "ingredientId": line["ingredientId"],
                "quantity": float(line["suggestedQty"]),
                "unit": line["unit"],
            }
            # Buy in the pack the vendor sells, where the list worked one out. The pack decides
            # the stored quantity; the `quantity` above is still required but ignored.
            packs = line.get("buyPacks") or []
            if packs and line.get("packFromVendor"):
                pack = packs[0]
                if pack.get("packSizeId") and pack.get("packCount"):
                    entry["packSizeId"] = pack["packSizeId"]
                    entry["packCount"] = float(pack["packCount"])
            po_lines.append(entry)

        payload = {
            "vendorId": vendor_id,
            "neededBy": min(needed) if needed else None,
            "deliveryLocation": "Temple store, rear gate",
            "notes": f"Order raised from the list for {vendor_name}.",
            "lines": po_lines,
        }
        try:
            made = manager.post("/api/v1/purchase-orders", payload)
            args.state.put(key, made["id"])
            args.state.put(f"{PHASE}.r{round_no}.ponumber.{vendor_name}", made["poNumber"])
            tally.made("purchase order",
                       f"{made['poNumber']} to {vendor_name}, {len(po_lines)} line(s)")
        except ApiError as e:
            tally.problem(f"order for {vendor_name}: {e}")

    # ---- send them --------------------------------------------------------
    # One is deliberately left as a draft: a temple always has an order somebody has not sent yet,
    # and the shopping list treats a draft differently from a sent one.
    step("sending them to the vendors")
    everything = manager.get("/api/v1/purchase-orders")
    drafts = [p for p in everything if p["status"] == "DRAFT"]

    # Which order stays unsent has to be decided from the whole order book, not from this run's
    # drafts. Deciding it from the drafts alone meant that on a second run there was only one
    # draft left — the one deliberately held back — and "the last of the drafts" was then also
    # "the only draft", so the phase sent the very order it had set out to keep. The highest
    # numbered order is the newest, which is the one a temple would still be working on.
    hold_back = max((p["poNumber"] for p in everything), default=None)

    for order in sorted(drafts, key=lambda p: p["poNumber"]):
        if order["poNumber"] == hold_back and len(everything) > 1:
            tally.skip("sent", f"{order['poNumber']} left as a draft on purpose")
            continue
        try:
            manager.post(f"/api/v1/purchase-orders/{order['id']}/send", {"sendAnyway": True})
            tally.made("sent", f"{order['poNumber']} to {order['vendorName']}")
        except ApiError as e:
            if e.code == "KMS-400051":
                tally.kept("sent", order["poNumber"])
            else:
                tally.problem(f"sending {order['poNumber']}: {e}")

    # ---- what the temple now owes -----------------------------------------
    step("the orders as they stand")
    orders = manager.get("/api/v1/purchase-orders")
    by_status: dict[str, int] = defaultdict(int)
    for order in orders:
        by_status[order["status"]] += 1
    for status, n in sorted(by_status.items()):
        info(f"  {status:20} {n}")

    total = 0.0
    for order in orders:
        detail = manager.get(f"/api/v1/purchase-orders/{order['id']}")
        for line in detail["lines"]:
            if line.get("expectedPrice"):
                total += float(line["expectedPrice"]) * float(line["quantity"])
    info(f"about Rs {total:,.0f} on order")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
