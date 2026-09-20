#!/usr/bin/env python3
"""
Phase 15 — the equipment, its servicing, and what the servicing cost.

Rajeev's brief: "Also simulate equipment service request, payment for service etc."

**There is no service request in this application, and no payment for a service.** That is worth
saying plainly rather than working around, because the brief asks for both:

- A service is **recorded after it has happened** — `POST /api/v1/equipment/{id}/services` with the
  date, the company, what they did and what it cost. There is no request, no approval, no
  scheduling of a visit. The nearest thing to a request is the **service schedule** (how often a
  machine needs looking at and who does it), which makes the machine show as `DUE_SOON` or
  `OVERDUE` on the equipment screen — that is the app's way of saying "this needs servicing".
- The cost goes on the service record and **nowhere else**. It never becomes a payable, never
  reaches the vendor invoice tables, and cannot be paid, part-paid or chased. It is a number
  written down after the fact.

So this phase builds what the application actually has: a register of equipment in different
conditions, service schedules that make some of it fall due, a history of services with their
costs, and one machine scrapped and one sent for repair. If Rajeev wants a service *request* with
an approval and a payment against it, that is a feature to build, not data to seed.

    python3 tools/seed/phase15_equipment.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase15"

# The kitchen's machines. Realistic for a temple cooking for several hundred a day.
EQUIPMENT = [
    {"name": "Commercial wet grinder, 15 L", "storageLocation": "Main Kitchen, batter corner",
     "serialNumber": "WG-15-2288", "purchaseCostInr": 58000, "acquisitionDate": "2024-03-12",
     "source": "PURCHASED", "condition": "GOOD",
     "schedule": (3, "MONTHS", "Sri Ganesh Kitchen Equipment", "+919845050001"),
     "services": [(120, "Sri Ganesh Kitchen Equipment", "Stone dressing and belt change", 2800),
                  (28, "Sri Ganesh Kitchen Equipment", "Motor bearing replaced, belt tightened",
                   4200)]},
    {"name": "Steam cooking vessel, 100 L", "storageLocation": "Main Kitchen, steam line",
     "serialNumber": "SV-100-0417", "purchaseCostInr": 46000, "acquisitionDate": "2023-08-02",
     "source": "PURCHASED", "condition": "GOOD",
     "schedule": (6, "MONTHS", "Bharath Steam Services", "+919845050002"),
     "services": [(95, "Bharath Steam Services", "Pressure valve tested and gasket replaced",
                   3600)]},
    {"name": "Dough kneader, 20 kg", "storageLocation": "Main Kitchen, roti section",
     "serialNumber": "DK-20-9931", "purchaseCostInr": 72000, "acquisitionDate": "2022-11-19",
     "source": "PURCHASED", "condition": "GOOD",
     "schedule": (4, "MONTHS", "Sri Ganesh Kitchen Equipment", "+919845050001"),
     "services": [(210, "Sri Ganesh Kitchen Equipment", "Gearbox oil change", 1900)],
     "condition_change": ("NEEDS_REPAIR",
                          "Makes a grinding noise under a full load. Cook will not use it.")},
    {"name": "Deep freezer, 500 L", "storageLocation": "Cold room",
     "serialNumber": "DF-500-1102", "purchaseCostInr": 51000, "acquisitionDate": "2025-01-27",
     "source": "DONATED", "condition": "GOOD",
     "schedule": (12, "MONTHS", "Cool Care Refrigeration", "+919845050003"),
     "services": []},
    {"name": "Wet grinder, 5 L (old)", "storageLocation": "Store room, back shelf",
     "serialNumber": "WG-05-0044", "purchaseCostInr": 21000, "acquisitionDate": "2018-06-04",
     "source": "PURCHASED", "condition": "GOOD",
     "schedule": None,
     "services": [(400, "Local repair shop", "Rewound the motor", 3200)],
     "condition_change": ("SCRAPPED",
                          "Drum cracked through. Not worth repairing at eight years old.")},
    {"name": "Gas burner, three-ring", "storageLocation": "Main Kitchen, range",
     "serialNumber": "GB-3R-5510", "purchaseCostInr": 16500, "acquisitionDate": "2024-09-15",
     "source": "PURCHASED", "condition": "GOOD",
     "schedule": (6, "MONTHS", "Bharath Steam Services", "+919845050002"),
     "services": [(60, "Bharath Steam Services", "Jets cleaned, one ring replaced", 1400)],
     "condition_change": ("IN_REPAIR", "Middle ring will not light. Away with the fitter.")},
]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 15 — equipment and servicing")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    today = date.today()
    existing = {e["name"]: e for e in admin.get("/api/v1/equipment?includeScrapped=true")}
    info(f"{len(existing)} piece(s) of equipment already on the register")

    step("the register")
    for wanted in EQUIPMENT:
        key = f"{PHASE}.item.{wanted['name']}"
        found = existing.get(wanted["name"])
        if found:
            args.state.put(key, found["id"])
            tally.kept("equipment", wanted["name"])
            continue
        if args.state.has(key):
            tally.kept("equipment", wanted["name"])
            continue
        if args.dry_run:
            info(f"would add {wanted['name']}")
            continue
        # Every machine is registered in the condition it was bought in, and the ones that have
        # since broken are changed below. Registering one as already NEEDS_REPAIR and then
        # "changing" it to NEEDS_REPAIR is refused — a condition change has to change something —
        # and it also loses the state history, which is the part a reader wants.
        payload = {k: wanted[k] for k in
                   ("name", "storageLocation", "serialNumber", "purchaseCostInr",
                    "acquisitionDate", "source", "condition") if k in wanted}
        try:
            made = admin.post("/api/v1/equipment", payload)
            args.state.put(key, made["id"])
            tally.made("equipment",
                       f"{wanted['name']} — {wanted['source'].lower()}, "
                       f"Rs {wanted['purchaseCostInr']:,}")
        except ApiError as e:
            if e.code == "KMS-400015":
                tally.kept("equipment", f"{wanted['name']} (serial already used)")
            else:
                tally.problem(f"{wanted['name']}: {e}")

    if args.dry_run:
        tally.report()
        return 0

    # ---- how often each one needs looking at -------------------------------
    # This is the closest the application has to a service *request*: a schedule makes a machine
    # fall due, and the equipment screen then shows it as DUE_SOON or OVERDUE.
    step("service schedules — the app's way of saying a machine needs attention")
    for wanted in EQUIPMENT:
        item_id = args.state.get(f"{PHASE}.item.{wanted['name']}")
        if not item_id or not wanted.get("schedule"):
            continue
        key = f"{PHASE}.schedule.{wanted['name']}"
        if args.state.has(key):
            tally.kept("service schedule")
            continue
        count, unit, company, phone = wanted["schedule"]
        try:
            admin.put(f"/api/v1/equipment/{item_id}/service-schedule", {
                "intervalCount": count, "intervalUnit": unit,
                "neverNeedsServicing": False,
                "serviceCompany": company, "serviceCompanyPhone": phone,
            })
            args.state.put(key, True)
            tally.made("service schedule",
                       f"{wanted['name']} — every {count} {unit.lower()}, {company}")
        except ApiError as e:
            tally.problem(f"schedule for {wanted['name']}: {e}")

    # ---- what has been done, and what it cost ------------------------------
    step("services recorded, with their cost")
    spent = 0.0
    for wanted in EQUIPMENT:
        item_id = args.state.get(f"{PHASE}.item.{wanted['name']}")
        if not item_id:
            continue
        for index, (days_ago, company, work, cost) in enumerate(wanted.get("services") or []):
            key = f"{PHASE}.service.{wanted['name']}.{index}"
            if args.state.has(key):
                tally.kept("service")
                spent += cost
                continue
            try:
                made = admin.post(f"/api/v1/equipment/{item_id}/services", {
                    "servicedOn": (today - timedelta(days=days_ago)).isoformat(),
                    "serviceCompany": company,
                    "workDone": work,
                    "costInr": cost,
                })
                args.state.put(key, made["id"])
                spent += cost
                tally.made("service",
                           f"{wanted['name']} — {work}, Rs {cost:,} "
                           f"({(today - timedelta(days=days_ago)).isoformat()})")
            except ApiError as e:
                if e.code == "KMS-400016":
                    tally.problem(f"{wanted['name']}: service date is in the future")
                else:
                    tally.problem(f"service on {wanted['name']}: {e}")

    # ---- the ones that are not working -------------------------------------
    step("condition changes")
    for wanted in EQUIPMENT:
        change = wanted.get("condition_change")
        item_id = args.state.get(f"{PHASE}.item.{wanted['name']}")
        if not change or not item_id:
            continue
        key = f"{PHASE}.condition.{wanted['name']}"
        if args.state.has(key):
            tally.kept("condition change")
            continue
        condition, reason = change
        try:
            admin.post(f"/api/v1/equipment/{item_id}/condition",
                       {"condition": condition, "reason": reason})
            args.state.put(key, True)
            tally.made("condition change", f"{wanted['name']} → {condition}: {reason}")
        except ApiError as e:
            if e.code == "KMS-400043":
                tally.kept("condition change", f"{wanted['name']} is already scrapped")
            else:
                tally.problem(f"condition of {wanted['name']}: {e}")

    # ---- what the register shows -------------------------------------------
    step("the register")
    register = admin.get("/api/v1/equipment?includeScrapped=true")
    by_condition: dict[str, int] = {}
    by_service: dict[str, int] = {}
    for item in register:
        by_condition[item["condition"]] = by_condition.get(item["condition"], 0) + 1
        status = item.get("serviceStatus") or "?"
        by_service[status] = by_service.get(status, 0) + 1

    for condition, n in sorted(by_condition.items()):
        info(f"  {condition:14} {n}")
    info("service status:")
    for status, n in sorted(by_service.items()):
        info(f"  {status:14} {n}")
    info(f"Rs {spent:,.0f} spent on servicing — recorded on the machines, not as a payable")

    for item in sorted(register, key=lambda e: e["name"]):
        info(f"  {item['name'][:34]:36} {item['condition']:13} "
             f"{str(item.get('serviceStatus')):14} next {item.get('nextServiceOn') or '-'}")

    missing = {"OVERDUE", "DUE_SOON"} - set(by_service)
    if missing:
        note(f"nothing is {', '.join(sorted(missing))} — the equipment screen will not show that "
             f"case. The schedules above decide it, against each machine's last service.")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
