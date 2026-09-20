#!/usr/bin/env python3
"""
Phase 10 — the sister kitchens ask the store for ingredients.

Rajeev's brief: "then start raising request for ingredients for the 3 kitchens that are just
requesting ingredients. Then simulate most of the request being approved some being denied then
run the issuing cycle record deliveries to the individual kitchens the whole cycle of issuing
ingredients to sister kitchens."

The cycle, and one thing it does not have: **there is no "received at the sister kitchen" step.**
The lifecycle is `DRAFT → SUBMITTED → APPROVED → ISSUED`, with `SUBMITTED → DENIED` off the side,
and issuing is the last event — it is what moves the stock out of the store. So "record deliveries
to the individual kitchens" is the issue, not a step after it. A kitchen that gets less than it
asked for raises a second request; there is no partial state.

What this builds, so the screen has every case on it:

    approved and issued in full     the ordinary week
    approved and issued short       the store had less than was asked for; a second request follows
    denied                          asked for something the store is holding for a festival
    approved, not yet issued        waiting at the counter
    submitted, undecided            on the manager's desk right now
    a draft                         somebody is still typing it

**Who does what matters here.** The request is raised by the kitchen that wants the food, the
decision is the Kitchen Manager's (`APPROVE_INGREDIENT_REQUESTS`), and the issue is the
storekeeper's (`ISSUE_INGREDIENTS`). Running the lot as one admin would prove nothing.

    python3 tools/seed/phase10_ingredient_requests.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN, kitchen_manager  # noqa: E402

PHASE = "phase10"

# What each kitchen cooks, and so what it asks the store for.
WANTS = {
    "Deity Kitchen": [
        ("Rice", 25, "KG"), ("Ghee", 6, "KG"), ("Sugar", 10, "KG"),
        ("Green cardamom", 0.2, "KG"), ("Cashew", 2, "KG"), ("Milk", 20, "L"),
    ],
    "Govindas Bliss": [
        ("Rice", 40, "KG"), ("Toor dal", 15, "KG"), ("Groundnut oil", 12, "L"),
        ("Potato", 20, "KG"), ("Carrot", 10, "KG"), ("Salt", 5, "KG"),
        ("Chilli powder", 1.5, "KG"),
    ],
    "Individual Person": [
        ("Rice", 5, "KG"), ("Moong dal", 2, "KG"), ("Ghee", 1, "KG"),
        ("Jaggery", 2, "KG"), ("Coconut", 6, "PIECES"),
    ],
}

# The dishes each request is for. The application refuses to submit a request that does not say
# what the food is for (KMS-400121) — a store cannot judge a request without knowing the dish.
DISHES = {
    "Deity Kitchen": [("Sweet rice offering", 20, "L"), ("Kesari Bath", 12, "L")],
    "Govindas Bliss": [("Vegetable pulao", 40, "L"), ("Dal tadka", 25, "L")],
    "Individual Person": [("Sponsored Huggi offering", 12, "L")],
}

# Six requests, each ending somewhere different, so every state on the screen has something in
# it. The kitchens are **whichever ones actually draw from the store**, not a hardcoded list:
# staging's sister kitchens are not the local ones, and naming them meant phase 09 invented
# "Govindas Bliss" beside staging's own "Govindas Restaurant" — the same kitchen twice.
ENDINGS = [
    ("issued_full", 12),
    ("issued_short", 10),
    ("denied", 8),
    ("approved_not_issued", 5),
    ("submitted", 3),
    ("draft", 1),
]

# What a kitchen that draws from the store asks for. Keyed by name where the temple has one of
# these, and otherwise the general list, because every kitchen needs rice, dal and oil.
GENERAL = [
    ("Rice", 30, "KG"), ("Toor dal", 10, "KG"), ("Groundnut oil", 8, "L"),
    ("Salt", 4, "KG"), ("Turmeric", 0.5, "KG"), ("Potato", 15, "KG"),
]
GENERAL_DISHES = [("Rice and dal for the day", 30, "L")]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 10 — ingredient requests")

    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)
    manager = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=True), args.tenant)

    kitchens = {k["name"]: k for k in admin.get("/api/v1/kitchens")}
    ingredients = {i["name"]: i for i in admin.get("/api/v1/ingredients")}

    drawing = [name for name, k in kitchens.items() if not k.get("usesMealPlanner")]
    info(f"{len(drawing)} kitchen(s) draw from the store: {', '.join(sorted(drawing))}")

    # A kitchen on the meal planner cannot raise a request at all, so asking for one is a mistake
    # worth catching here rather than as a 409 halfway through.
    for kitchen_name in drawing:
        if kitchens.get(kitchen_name, {}).get("usesMealPlanner"):
            tally.problem(f"{kitchen_name} plans its own meals, so it cannot request ingredients "
                          f"(KMS-400110). Run phase 09 first.")
            return tally.report()

    existing = {r["reference"]: r for r in admin.get("/api/v1/ingredient-requests")}
    if existing:
        note(f"{len(existing)} request(s) already on the system")

    # Spread the six endings across whatever kitchens draw from the store, round-robin.
    drawing_kitchens = sorted(drawing)
    if not drawing_kitchens:
        tally.problem("no kitchen draws from the store, so nobody can request anything. "
                      "Run phase 09 first.")
        return tally.report()
    plan = [(drawing_kitchens[i % len(drawing_kitchens)], ending, days_ago)
            for i, (ending, days_ago) in enumerate(ENDINGS)]

    step("raising, deciding and issuing")
    for position, (kitchen_name, ending, days_ago) in enumerate(plan):
        key = f"{PHASE}.request.{position}"
        if args.state.has(key):
            tally.kept("request", f"{kitchen_name} ({ending.replace('_', ' ')})")
            continue

        kitchen = kitchens.get(kitchen_name)
        if not kitchen:
            tally.problem(f"no kitchen called {kitchen_name}")
            continue

        wants = WANTS.get(kitchen_name, GENERAL)
        lines = []
        for name, quantity, unit in wants:
            ingredient = ingredients.get(name)
            if not ingredient:
                continue
            lines.append({"ingredientId": ingredient["id"], "quantity": quantity, "unit": unit,
                          "note": None})
        if not lines:
            tally.problem(f"{kitchen_name}: none of what it wants is in the catalogue")
            continue

        payload = {
            "kitchenId": kitchen["id"],
            "neededOn": (date.today() + timedelta(days=2)).isoformat(),
            "purpose": f"{kitchen_name} — "
                       f"{', '.join(d[0] for d in DISHES.get(kitchen_name, GENERAL_DISHES))}",
            "lines": lines,
            "dishes": [{"dishName": d, "quantity": q, "unit": u}
                       for d, q, u in DISHES.get(kitchen_name, GENERAL_DISHES)],
        }

        if args.dry_run:
            info(f"would raise for {kitchen_name}: {len(lines)} line(s), ending {ending}")
            continue

        try:
            # Raised by the Temple Admin on the kitchen's behalf. The right person would be that
            # kitchen's own in-charge, but the UAT accounts are all attached to Main Kitchen, so
            # the honest thing is to say so rather than pretend otherwise.
            made = admin.post("/api/v1/ingredient-requests", payload)
            request_id = made["id"]
            args.state.put(key, request_id)
        except ApiError as e:
            tally.problem(f"{kitchen_name}: {e}")
            continue

        detail = admin.get(f"/api/v1/ingredient-requests/{request_id}")
        reference = detail["request"]["reference"]

        if ending == "draft":
            tally.made("request", f"{reference} {kitchen_name} — left as a draft, still being typed")
            continue

        try:
            admin.post(f"/api/v1/ingredient-requests/{request_id}/submit")
        except ApiError as e:
            tally.problem(f"submitting {reference}: {e}")
            continue

        if ending == "submitted":
            tally.made("request", f"{reference} {kitchen_name} — submitted, waiting on a decision")
            continue

        if ending == "denied":
            try:
                manager.post(f"/api/v1/ingredient-requests/{request_id}/deny", {
                    "note": "The store is holding this ghee and jaggery for Radhastami. "
                            "Ask again on the 21st.",
                })
                tally.made("request", f"{reference} {kitchen_name} — denied, with a reason")
            except ApiError as e:
                tally.problem(f"denying {reference}: {e}")
            continue

        try:
            manager.post(f"/api/v1/ingredient-requests/{request_id}/approve", {
                "note": "Approved. Collect from the store before eleven.",
            })
        except ApiError as e:
            tally.problem(f"approving {reference}: {e}")
            continue

        if ending == "approved_not_issued":
            tally.made("request",
                       f"{reference} {kitchen_name} — approved, waiting at the counter")
            continue

        # ---- issuing: the last event, and the one that moves the stock ----
        try:
            if ending == "issued_short":
                # The store had less than was asked for. Every line is named with what actually
                # went out, and the kitchen raises a second request for the rest — there is no
                # partial state to sit in.
                fresh = admin.get(f"/api/v1/ingredient-requests/{request_id}")
                short_lines = []
                for index, line in enumerate(fresh["lines"]):
                    given = float(line["quantity"]) * (0.6 if index % 2 == 0 else 1.0)
                    short_lines.append({"lineId": line["id"], "quantity": round(given, 3),
                                        "unit": line["unit"]})
                admin.post(f"/api/v1/ingredient-requests/{request_id}/issue", {
                    "lines": short_lines,
                    "note": "Issued what the store had. Rice and oil were short; "
                            "raise a second request for the balance.",
                })
                tally.made("request",
                           f"{reference} {kitchen_name} — issued short, "
                           f"{len(short_lines)} line(s)")
            else:
                # An empty body issues every line at the approved quantity.
                admin.post(f"/api/v1/ingredient-requests/{request_id}/issue", {
                    "note": "Collected by the kitchen in-charge.",
                })
                tally.made("request", f"{reference} {kitchen_name} — issued in full")
        except ApiError as e:
            if e.code == "KMS-400118":
                tally.problem(f"{reference}: not enough stock to issue — {e}")
            else:
                tally.problem(f"issuing {reference}: {e}")

    # ---- what the store now shows -----------------------------------------
    step("the requests")
    requests = admin.get("/api/v1/ingredient-requests")
    by_status: dict[str, int] = {}
    for request in requests:
        by_status[request["status"]] = by_status.get(request["status"], 0) + 1
    for status, n in sorted(by_status.items()):
        info(f"  {status:12} {n}")

    for request in sorted(requests, key=lambda r: r["reference"]):
        info(f"  {request['reference']:16} {request['kitchenName']:20} {request['status']:10} "
             f"{request['lineCount']} line(s)")

    issued = admin.get("/api/v1/inventory/movements?type=ISSUE&limit=500")
    info(f"{len(issued)} stock movement(s) from issuing")

    missing = {"ISSUED", "DENIED", "APPROVED", "SUBMITTED", "DRAFT"} - set(by_status)
    if missing:
        note(f"no request is in {', '.join(sorted(missing))} — the screen will not show that case")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
