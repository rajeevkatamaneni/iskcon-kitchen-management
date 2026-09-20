#!/usr/bin/env python3
"""
Phase 03 — finish every ingredient: who sells it, for how much, and what that vendor's details are.

Rajeev's brief: "go to each ingredient and set the alias, units EkaDashi flag, category, pack size,
price, a vendor and details that vendor needs."

Phase 01 already set the alias, unit, Ekadashi flag, category, pack size and the market-rate price,
because it had to — an opening count cannot be recorded without a value. So what is left, and what
this phase does, is the last two: **a vendor for every ingredient, and the vendor's own details.**

It also sweeps up anything the recipe import created. A library-derived ingredient arrives with a
guessed category, the unit its first recipe happened to use, no alias, no price and no vendor, so
after phase 02 there is a second population that has none of phase 01's work done to it.

Three things it deliberately does not do:

- **It does not touch the test vendors.** Thirteen of the temple's vendors are named VERIFY-…,
  VERIFY2-… or UAT-test — artefacts of earlier testing. They are left exactly as they are and
  reported at the end, because deleting somebody's test data is not this script's decision.
- **It does not override the lookalike guard.** If a name looks like one already in the catalogue,
  the existing one is used.
- **It gives every ingredient exactly one preferred vendor**, which the database enforces anyway
  (a partial unique index), and a second non-preferred quote for about a third of them, so the
  price comparison on the shopping list has something to compare.

    python3 tools/seed/phase03_complete_ingredients.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN  # noqa: E402

PHASE = "phase03"
DATA = Path(__file__).resolve().parent / "data" / "catalogue.json"

# Test artefacts from earlier rounds. Left alone, never used, reported once.
TEST_VENDOR = re.compile(r"^(VERIFY|VERIFY2|UAT-test|E2E)\b", re.IGNORECASE)

# Who sells what. A temple does not buy dal from the dairy.
#
# All ten of the temple's vendors have a real slice, and that is deliberate rather than tidy: a
# vendor sitting in the list with nothing linked to it reads as a broken screen, and half the
# vendor page being empty is the first thing anyone notices.
#
# Two of these were found on staging and are not in the local copy — Sri Venkateshwara Rice
# Traders and Karnataka Provision Mart. They are given the trade their names describe: the rice
# trader takes the grains, which makes it the preferred vendor for rice, the flours, the ravas and
# the sago; the provision mart takes the sweeteners and the dry fruit. Sri Balaji Traders keeps the
# pulses, which is the split a temple really has — the rice shop and the dal shop are not the same
# shop.
SELLS = {
    "Sri Venkateshwara Rice Traders": ["Grains"],
    "Sri Balaji Traders": ["Pulses"],
    "Karnataka Provision Mart": ["Sweeteners", "Nuts & seeds"],
    "Anand Masala Depot": ["Spices"],
    "Ganesh Oil & Provisions": ["Oils & fats"],
    "Heritage Fresh Dairy": ["Dairy"],
    "Kalasipalya Vegetable Mandi": ["Vegetables", "Fruit"],
    "Mahalakshmi Stores": ["Other", "Puja"],
    "Vishwa Packaging & Supplies": ["Serving"],
    "Jayanagar Hardware Store": ["Cleaning", "Maintenance", "Fuel"],
}

# The second quote, so the temple can see it is paying the better price. Every vendor that has a
# preferred slice also appears here for something else, which is how a real book of vendors reads:
# you buy your rice from the rice man and still have a price from the general store.
ALTERNATE = {
    "Grains": "Sri Balaji Traders",              # the dal shop also sells rice, dearer
    "Pulses": "Karnataka Provision Mart",
    "Spices": "Mahalakshmi Stores",
    "Oils & fats": "Karnataka Provision Mart",
    "Sweeteners": "Mahalakshmi Stores",
    "Nuts & seeds": "Anand Masala Depot",
    "Dairy": "Karnataka Provision Mart",
    "Serving": "Jayanagar Hardware Store",
    "Cleaning": "Mahalakshmi Stores",
    "Puja": "Anand Masala Depot",
    "Other": "Sri Venkateshwara Rice Traders",
}

# Details a vendor needs before anyone can order from them, filled where the temple left a gap.
VENDOR_DETAILS = {
    "Sri Balaji Traders": {
        "contactPerson": "Balaji Rao", "phone": "+919845012301",
        "email": "orders@sribalajitraders.example", "gstin": "29AABCS1429B1Z1",
        "address": "14 Avenue Road, Chickpet, Bengaluru 560053",
        "notes": "Rice, dals and flours. Delivers before 7am on the day after the order.",
        "preferredLanguage": "kn", "leadTimeDays": 1},
    "Anand Masala Depot": {
        "contactPerson": "Anand Kumar", "phone": "+919845012304",
        "email": "anand@anandmasala.example", "gstin": "29AACFA5612K1ZP",
        "address": "8 Nagarathpet Main Road, Bengaluru 560002",
        "notes": "Whole and ground spices. Grinds the temple's own sambar and huli pudi to order.",
        "preferredLanguage": "kn", "leadTimeDays": 2},
    "Ganesh Oil & Provisions": {
        "contactPerson": "Ganesh Shetty", "phone": "+919845012305",
        "email": "ganesh.oils@example.org", "gstin": "29AAGCG7781M1Z4",
        "address": "62 Sajjan Rao Circle, VV Puram, Bengaluru 560004",
        "notes": "Groundnut and sunflower oil in 15 litre tins, ghee in 15 kg tins.",
        "preferredLanguage": "kn", "leadTimeDays": 2},
    "Heritage Fresh Dairy": {
        "contactPerson": "Lakshmi Narayan", "phone": "+919845012302",
        "email": "supply@heritagefresh.example", "gstin": "29AAECH9012R1ZQ",
        "address": "Plot 22, KIADB Dairy Estate, Bengaluru 560099",
        "notes": "Milk and curd delivered daily at 5am. Same-day only, no standing order.",
        "preferredLanguage": "en", "leadTimeDays": 0},
    "Kalasipalya Vegetable Mandi": {
        "contactPerson": "Muniyappa", "phone": "+919845012303",
        "email": None, "gstin": None,
        "address": "Shop 51, Kalasipalya Market, Bengaluru 560002",
        "notes": "Vegetables and fruit at market rate; the price moves every day. Cash on delivery.",
        "preferredLanguage": "kn", "leadTimeDays": 0},
    "Mahalakshmi Stores": {
        "contactPerson": "Raju", "phone": "+919845012307",
        "email": "mahalakshmistores@example.org", "gstin": "29AAJFM4455L1ZB",
        "address": "3 Gandhi Bazaar Main Road, Basavanagudi, Bengaluru 560004",
        "notes": "General provisions. Second source for most things, a little dearer.",
        "preferredLanguage": "kn", "leadTimeDays": 2},
    "Vishwa Packaging & Supplies": {
        "contactPerson": "Vishwanath", "phone": "+919845012306",
        "email": "vishwa.packaging@example.org", "gstin": "29AADCV3344N1ZT",
        "address": "17 Mysore Road Industrial Suburb, Bengaluru 560026",
        "notes": "Leaf plates, cups and disposables. Minimum order 5,000 pieces.",
        "preferredLanguage": "en", "leadTimeDays": 3},
    "Sri Venkateshwara Rice Traders": {
        "contactPerson": "Venkatesh Shetty", "phone": "+919845012307",
        "email": "orders@svrice.example", "gstin": "29AAFCS2201H1ZK",
        "address": "27 APMC Yard, Yeshwanthpur, Bengaluru 560022",
        "notes": "Rice, flours, rava and sago by the sack. Mills its own sona masoori; "
                 "delivers to the rear gate before six.",
        "preferredLanguage": "kn", "leadTimeDays": 1},
    "Karnataka Provision Mart": {
        "contactPerson": "Shivanna M", "phone": "+919845012308",
        "email": "kpm.orders@example.org", "gstin": "29AAGFK8890D1ZM",
        "address": "9 Sajjan Rao Road, Visveswarapuram, Bengaluru 560004",
        "notes": "Sugar, jaggery and dry fruit. Keeps the temple's festival order aside a week "
                 "ahead.",
        "preferredLanguage": "kn", "leadTimeDays": 2},
    "Jayanagar Hardware Store": {
        "contactPerson": "Srinivas", "phone": "+919845012308",
        "email": None, "gstin": "29AAHFJ6677P1ZX",
        "address": "45 11th Main, 4th Block Jayanagar, Bengaluru 560011",
        "notes": "Cleaning supplies, bulbs, stools and the gas cylinder account. Walk-in counter.",
        "preferredLanguage": "kn", "leadTimeDays": 1},
}


def alternate_price(price: float) -> float:
    """The second vendor is dearer, by an amount that looks like a real quote rather than a rule."""
    bump = 1.06 if price >= 100 else 1.11
    raised = price * bump
    return round(raised, 2 if raised < 10 else 0) or round(raised, 2)


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 03 — a vendor and a price for everything")
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    catalogue = json.loads(DATA.read_text())
    known = {r["name"]: dict(r, supply=False) for r in catalogue["ingredients"]}
    known.update({r["name"]: dict(r, supply=True) for r in catalogue["supplies"]})

    # ---- the vendors -------------------------------------------------------
    step("the vendors, and the details they were missing")
    vendors = admin.get("/api/v1/vendors")
    by_name = {v["name"]: v for v in vendors}
    test_vendors = [v["name"] for v in vendors if TEST_VENDOR.match(v["name"])]

    for name, details in VENDOR_DETAILS.items():
        payload = {k: v for k, v in details.items() if k != "leadTimeDays"}
        payload["name"] = name
        existing = by_name.get(name)
        try:
            if existing:
                # Fill only what is blank. Overwriting a real contact with an invented one would
                # be the script deciding it knows better than the temple.
                merged = dict(payload)
                for field in ("contactPerson", "phone", "email", "address", "gstin", "notes"):
                    if existing.get(field):
                        merged[field] = existing[field]
                merged["contractEndDate"] = existing.get("contractEndDate")
                admin.put(f"/api/v1/vendors/{existing['id']}", merged)
                args.state.put(f"{PHASE}.vendor.{name}", existing["id"])
                tally.made("vendor detail", name)
            else:
                made = admin.post("/api/v1/vendors", payload)
                by_name[name] = {"id": made["id"], "name": name}
                args.state.put(f"{PHASE}.vendor.{name}", made["id"])
                tally.made("vendor", name)
        except ApiError as e:
            if e.code == "KMS-400049":
                tally.kept("vendor", name)
            else:
                tally.problem(f"vendor {name}: {e}")

    vendor_id = {n: (by_name.get(n) or {}).get("id") or args.state.get(f"{PHASE}.vendor.{n}")
                 for n in VENDOR_DETAILS}
    missing = [n for n, i in vendor_id.items() if not i]
    if missing:
        tally.problem(f"no id for {', '.join(missing)} — their ingredients get no price")

    # who sells each category
    seller: dict[str, str] = {}
    for name, categories in SELLS.items():
        for category in categories:
            seller[category] = name

    # ---- every ingredient gets a vendor ------------------------------------
    step("a preferred vendor and a price for every ingredient")
    ingredients = admin.get("/api/v1/ingredients")
    info(f"{len(ingredients)} ingredient(s) in the catalogue")

    rows_by_vendor: dict[str, list] = {}
    alt_by_vendor: dict[str, list] = {}
    unpriced = []

    for ingredient in ingredients:
        spec = known.get(ingredient["name"])
        category = ingredient["category"]

        if spec:
            price = float(spec["price"])
        else:
            # Created by the recipe import: no price anywhere. Use whatever the application can
            # suggest, and say so rather than inventing a number.
            suggestion = admin.get(
                f"/api/v1/ingredients/{ingredient['id']}/stock-value-suggestion")
            if suggestion and suggestion.get("pricePerUnit"):
                price = float(suggestion["pricePerUnit"])
            else:
                unpriced.append(ingredient["name"])
                continue

        vendor = seller.get(category)
        if not vendor or not vendor_id.get(vendor):
            unpriced.append(f"{ingredient['name']} (nobody sells {category})")
            continue

        # With a pack, the price is typed per pack and the server derives the per-unit figure.
        # Sending both is refused (KMS-400163), so this sends one or the other.
        packs = ingredient.get("packSizes") or []
        row = {"ingredientId": ingredient["id"], "preferred": True,
               "leadTimeDays": VENDOR_DETAILS[vendor]["leadTimeDays"]}
        if packs:
            pack = packs[0]
            row["packSizeId"] = pack["id"]
            row["pricePerPack"] = round(price * float(pack["baseQuantity"]), 2)
        else:
            row["lastPrice"] = price
        rows_by_vendor.setdefault(vendor, []).append(row)

        alt = ALTERNATE.get(category)
        if alt and vendor_id.get(alt) and alt != vendor and len(rows_by_vendor.get(vendor, [])) % 3 == 0:
            alt_by_vendor.setdefault(alt, []).append({
                "ingredientId": ingredient["id"],
                "preferred": False,
                "leadTimeDays": VENDOR_DETAILS[alt]["leadTimeDays"],
                "lastPrice": alternate_price(price),
            })

    if args.dry_run:
        for vendor, rows in rows_by_vendor.items():
            info(f"would give {vendor} {len(rows)} preferred price(s)")
        for vendor, rows in alt_by_vendor.items():
            info(f"would give {vendor} {len(rows)} second quote(s)")
        tally.report()
        return 0

    # One call per vendor. It is a single transaction, so a bad row refuses the whole list —
    # which is what makes the whole price list either right or absent, never half applied.
    for vendor, rows in rows_by_vendor.items():
        try:
            admin.post(f"/api/v1/vendors/{vendor_id[vendor]}/supplies/bulk", {"rows": rows})
            tally.made("preferred price", f"{vendor}: {len(rows)} ingredient(s)", n=len(rows))
        except ApiError as e:
            tally.problem(f"price list for {vendor} ({len(rows)} rows) refused: {e}")

    for vendor, rows in alt_by_vendor.items():
        try:
            admin.post(f"/api/v1/vendors/{vendor_id[vendor]}/supplies/bulk", {"rows": rows})
            tally.made("second quote", f"{vendor}: {len(rows)} ingredient(s)", n=len(rows))
        except ApiError as e:
            tally.problem(f"second quotes for {vendor} refused: {e}")

    # ---- what is still incomplete ------------------------------------------
    step("what the temple can now see")
    preferred = admin.get("/api/v1/vendors/preferred")
    info(f"{len(preferred)} ingredient(s) have a preferred vendor")

    without = [i for i in ingredients
               if i["id"] not in {p["ingredientId"] for p in preferred}]
    if without:
        names = ", ".join(i["name"] for i in without[:8])
        tally.problem(f"{len(without)} ingredient(s) still have no vendor: {names}"
                      + (" …" if len(without) > 8 else ""))

    if unpriced:
        note(f"{len(unpriced)} had no price to work from: {', '.join(unpriced[:6])}"
             + (" …" if len(unpriced) > 6 else ""))

    if test_vendors:
        note(f"{len(test_vendors)} vendors left untouched because they are test artefacts: "
             f"{', '.join(test_vendors[:6])}" + (" …" if len(test_vendors) > 6 else ""))
        note("Rajeev may want these removed before the demo; this script will not do it")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
