#!/usr/bin/env python3
"""
Phase 14 — the wish list, and people giving to it.

Rajeev's brief: "simulate wish list of equipment. The temple wants simulate donations for
equipment that is listed. Also simulate general donations and cash donations that are done in
person, etc."

So: a wish list with real things on it at real prices, gifts against those items that move them
towards being paid for, and the everyday giving at the counter — cash in the hundi, a sack of rice
handed in, a mixer somebody bought for the kitchen.

**Online giving cannot be simulated, and that is not a limitation of this script.** The public
donor endpoint was withdrawn on 2026-08-29; what is left creates a `PENDING` donation and waits
for a payment gateway to confirm it. There is no gateway here — payments are stubbed — so an
online gift would sit `PENDING` for thirty minutes and then go `EXPIRED`. Anything that has to
land as money received is recorded the way the counter really records it, with
`POST /api/v1/donations` and `cashAmountInr`.

**Cash and goods cannot go on one donation.** The application refuses it and says to record two,
which is right: a person who hands over ₹5,000 and a sack of rice has given two different things
and the ledger should say so.

**Who records it:** `MANAGE_INVENTORY`, so the kitchen staff at the gate can, which is the whole
point — the person taking the sack is the person who writes it down. Voiding one is the Temple
Admin's alone.

    python3 tools/seed/phase14_giving.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import KITCHEN_STAFF, TEMPLE_ADMIN  # noqa: E402

PHASE = "phase14"

# What the temple is asking for. Real kitchen equipment at real Indian prices.
WISHLIST = [
    {"title": "Stainless steel prasadam counter", "priceInr": 85000, "quantityWanted": 1,
     "category": "EQUIPMENT",
     "description": "A six-foot serving counter for the hall, so the queue moves at festivals."},
    {"title": "Commercial wet grinder, 15 litre", "priceInr": 62000, "quantityWanted": 2,
     "category": "EQUIPMENT",
     "description": "For idli and dosa batter. The present one is twelve years old and stops "
                    "under load."},
    {"title": "Steam cooking vessel, 100 litre", "priceInr": 48000, "quantityWanted": 3,
     "category": "EQUIPMENT",
     "description": "Rice for a thousand people, without a cook standing over it."},
    {"title": "Deep freezer, 500 litre", "priceInr": 54000, "quantityWanted": 1,
     "category": "EQUIPMENT",
     "description": "So vegetables bought at the mandi on a good day keep until they are needed."},
    {"title": "Insulated food delivery carrier", "priceInr": 9500, "quantityWanted": 6,
     "category": "EQUIPMENT",
     "description": "For the outside programmes. Food arrives hot instead of warm."},
    {"title": "A month of rice for the free kitchen", "priceInr": 128000, "quantityWanted": 1,
     "category": "CONSUMABLE",
     "description": "Two thousand kilos, which is what the annadana kitchen gets through."},
]

# Gifts towards those items, in the order the wish list is written.
SPONSORSHIPS = [
    (0, "Srinivasa Rao", "+919845040001", 85000, 21),    # pays for the counter outright
    (1, "Meera Krishnan", "+919845040002", 31000, 18),   # half a grinder
    (1, "Anonymous", None, 31000, 14),                   # the other half
    (2, "Vasudeva Prabhu", "+919845040003", 15000, 12),  # part of a vessel
    (5, "Hemalatha Iyer", "+919845040004", 25000, 9),    # towards the rice
]

# Everyday giving at the counter.
CASH_GIFTS = [
    ("Ramesh Gupta", "+919845041001", 5100, 24, "Hundi, Sunday morning"),
    ("Anonymous", None, 2500, 22, "Hundi"),
    ("Lakshmi Devi", "+919845041002", 11000, 20, "For Janmastami prasadam"),
    ("Anonymous", None, 501, 19, "Hundi"),
    ("Krishna Murthy", "+919845041003", 25000, 17, "Annual gift, asked for an 80G receipt"),
    ("Sudha Rani", "+919845041004", 1100, 15, "Birthday offering"),
    ("Anonymous", None, 3300, 12, "Hundi"),
    ("Gopinath Shenoy", "+919845041005", 7500, 9, "For the free kitchen"),
    ("Anonymous", None, 1500, 6, "Hundi"),
    ("Padmavathi Bai", "+919845041006", 15000, 3, "In memory of her husband"),
]

# Gifts from the two devotees who have an account on the site.
#
# **Why these are written out separately.** A gift is "mine" to the person who gave it if the
# donation carries their account id, or a phone or email the application has verified for them —
# never their name, because a name is not an identity and two devotees are called Govind Das. Every
# other gift in this file is recorded at the counter with a name and a phone typed by a volunteer,
# which is exactly right for somebody who walks in, and means nothing to any account. So both donor
# accounts signed in and saw an empty page under their own giving, which is a poor showing for a
# feature whose whole point is that people can see what they gave.
#
# These carry the account's **email**, the address Firebase has verified, so the gift reaches the
# person who gave it. Keeping it to a handful is deliberate: most of a temple's book is people with
# no login, and making every gift belong to an account would be the less truthful picture.
#
# Name, email, phone, amount, days ago, what it was for.
ACCOUNT_GIFTS = [
    ("Anantha Rao", "ikms.donor.1@trading4good.org", "+919845041007",
     11000, 21, "Monthly gift, standing since last year"),
    ("Anantha Rao", "ikms.donor.1@trading4good.org", "+919845041007",
     2100, 7, "Towards the Radhastami feast"),
    ("Kamala Iyer", "ikms.donor.2@trading4good.org", "+919845041008",
     5000, 16, "For the free kitchen"),
    ("Kamala Iyer", "ikms.donor.2@trading4good.org", "+919845041008",
     1116, 4, "Birthday offering for her grandson"),
]

# Goods handed in at the gate. Ingredient name, how much, unit.
GOODS = [
    ("Vidyashankar Bhat", "+919845042001", [("Rice", 100, "KG")], 5800, 23,
     "Two sacks of sona masoori, carried in himself"),
    ("Anonymous", None, [("Ghee", 15, "KG")], 9750, 19, "A tin of ghee for the deity kitchen"),
    ("Sharada Amma", "+919845042002", [("Toor dal", 50, "KG"), ("Moong dal", 25, "KG")], 11625, 16,
     "Dal for the free kitchen"),
    ("Ravi Shankar", "+919845042003", [("Sugar", 50, "KG"), ("Jaggery", 25, "KG")], 4150, 11,
     "For the festival sweets"),
    ("Anonymous", None, [("Coconut", 100, "PIECES")], 4500, 7, "A load of coconuts from a grove"),
]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 14 — the wish list and the giving")

    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)
    counter = sign_in(args.api, KITCHEN_STAFF[0], args.tenant)   # the person at the gate

    today = date.today()

    # ---- the wish list -----------------------------------------------------
    step("what the temple is asking for")
    every = admin.get("/api/v1/wishlist?includeArchived=true")
    existing = {w["title"]: w for w in every}
    info(f"{len(existing)} item(s) already on the list")

    # Do not ask for something the temple is already asking for. Matching on the exact title is
    # not enough: staging wanted a "Steam cooker, 100 litre" and this list wanted a "Steam cooking
    # vessel, 100 litre", so the page ended up asking for the same cooker twice. Two items are
    # taken to be the same thing when the words that carry the meaning match — the make and the
    # size — ignoring the ones that do not.
    NOISE = {"a", "an", "the", "of", "set", "litre", "liter", "l", "kg", "for", "and", "steel",
             "stainless", "commercial", "industrial", "month", "vessel", "vessels", "unit"}

    def stem(word: str) -> str:
        # Crude on purpose. It only has to make "cooker" and "cooking" the same word, which is
        # what separated the temple's "Steam cooker, 100 litre" from this list's "Steam cooking
        # vessel, 100 litre" — the same cooker, asked for twice on one page.
        for suffix in ("ing", "ers", "er", "es", "s"):
            if len(word) > len(suffix) + 2 and word.endswith(suffix):
                return word[: -len(suffix)]
        return word

    def shape(title: str) -> frozenset:
        words = "".join(c.lower() if c.isalnum() else " " for c in title).split()
        return frozenset(stem(w) for w in words if w not in NOISE and not w.isdigit())

    taken = {shape(w["title"]): w for w in every}

    for wanted in WISHLIST:
        key = f"{PHASE}.wish.{wanted['title']}"
        found = existing.get(wanted["title"]) or taken.get(shape(wanted["title"]))
        if found and found["title"] != wanted["title"]:
            note(f"the temple already asks for \"{found['title']}\", so \"{wanted['title']}\" "
                 f"is not added as well")
        if found:
            args.state.put(key, found["id"])
            tally.kept("wish-list item", wanted["title"])
            continue
        if args.state.has(key):
            tally.kept("wish-list item", wanted["title"])
            continue
        if args.dry_run:
            info(f"would add {wanted['title']} at Rs {wanted['priceInr']:,}")
            continue
        try:
            made = admin.post("/api/v1/wishlist", wanted)
            args.state.put(key, made["id"])
            tally.made("wish-list item",
                       f"{wanted['title']} — Rs {wanted['priceInr']:,} × {wanted['quantityWanted']}")
        except ApiError as e:
            tally.problem(f"{wanted['title']}: {e}")

    if args.dry_run:
        tally.report()
        return 0

    wishlist = admin.get("/api/v1/wishlist")
    by_position = {i: w for i, w in enumerate(sorted(wishlist, key=lambda w: w.get("sortOrder", 0)))}

    # ---- gifts against a listed item ---------------------------------------
    step("people paying for what is on the list")
    for index, (position, who, phone, amount, days_ago) in enumerate(SPONSORSHIPS):
        key = f"{PHASE}.sponsor.{index}"
        if args.state.has(key):
            tally.kept("sponsorship")
            continue
        item = by_position.get(position)
        if not item:
            tally.skip("sponsorship", f"no wish-list item at position {position}")
            continue
        if item.get("status") != "ACTIVE":
            tally.skip("sponsorship", f"{item['title']} is {item.get('status')}")
            continue

        # Never give more than the thing costs. The amounts here were written against this
        # script's own prices, and the temple's own item can cost something else: a gift of
        # Rs 85,000 landed on a counter priced at Rs 46,500 and the page then read
        # "Rs 85,000 of Rs 46,500", which looks like a fault rather than generosity.
        outstanding = (float(item["priceInr"]) * int(item["quantityWanted"])
                       - float(item.get("paidInr") or 0))
        if outstanding <= 0:
            tally.skip("sponsorship", f"{item['title']} is already paid for")
            continue
        amount = min(amount, round(outstanding))

        anonymous = who == "Anonymous"
        payload = {
            "anonymous": anonymous,
            "donorName": None if anonymous else who,
            "donorPhone": None if anonymous else phone,
            "cashAmountInr": amount,
            "donatedOn": (today - timedelta(days=days_ago)).isoformat(),
            "wishlistItemId": item["id"],
            "notes": f"Towards {item['title']}",
        }
        try:
            made = counter.post("/api/v1/donations", payload)
            args.state.put(key, made["id"])
            tally.made("sponsorship",
                       f"{who} gave Rs {amount:,} towards {item['title']}")
        except ApiError as e:
            if e.code == "KMS-400068":
                tally.skip("sponsorship", f"{item['title']} is no longer taking gifts")
            else:
                tally.problem(f"{who} towards {item['title']}: {e}")

    # ---- cash at the counter -----------------------------------------------
    step("cash given in person")
    for index, (who, phone, amount, days_ago, why) in enumerate(CASH_GIFTS):
        key = f"{PHASE}.cash.{index}"
        if args.state.has(key):
            tally.kept("cash gift")
            continue
        anonymous = who == "Anonymous"
        payload = {
            "anonymous": anonymous,
            "donorName": None if anonymous else who,
            "donorPhone": None if anonymous else phone,
            "cashAmountInr": amount,
            "donatedOn": (today - timedelta(days=days_ago)).isoformat(),
            "notes": why,
        }
        try:
            made = counter.post("/api/v1/donations", payload)
            args.state.put(key, made["id"])
            tally.made("cash gift",
                       f"{'someone anonymous' if anonymous else who} — Rs {amount:,}, {why}")
        except ApiError as e:
            tally.problem(f"cash gift from {who}: {e}")

    # ---- the two devotees who have an account -------------------------------
    step("gifts from devotees with an account on the site")
    for index, (who, email, phone, amount, days_ago, why) in enumerate(ACCOUNT_GIFTS):
        key = f"{PHASE}.account.{index}"
        if args.state.has(key):
            tally.kept("gift from an account holder")
            continue
        payload = {
            "anonymous": False,
            "donorName": who,
            "donorPhone": phone,
            # The one field that makes the gift theirs. Without it the row is a name on a page.
            "donorEmail": email,
            "cashAmountInr": amount,
            "donatedOn": (today - timedelta(days=days_ago)).isoformat(),
            "notes": why,
        }
        try:
            made = counter.post("/api/v1/donations", payload)
            args.state.put(key, made["id"])
            tally.made("gift from an account holder", f"{who} — Rs {amount:,}, {why}")
        except ApiError as e:
            tally.problem(f"gift from {who}: {e}")

    # ---- goods handed in ----------------------------------------------------
    step("goods handed in at the gate")
    ingredients = {i["name"]: i for i in admin.get("/api/v1/ingredients")}
    for index, (who, phone, items, value, days_ago, why) in enumerate(GOODS):
        key = f"{PHASE}.goods.{index}"
        if args.state.has(key):
            tally.kept("gift in kind")
            continue
        lines = []
        for name, quantity, unit in items:
            ingredient = ingredients.get(name)
            if not ingredient:
                continue
            lines.append({"ingredientId": ingredient["id"], "quantity": quantity, "unit": unit})
        if not lines:
            tally.skip("gift in kind", f"{who}: none of it is in the catalogue")
            continue

        anonymous = who == "Anonymous"
        payload = {
            "anonymous": anonymous,
            "donorName": None if anonymous else who,
            "donorPhone": None if anonymous else phone,
            "estimatedValueInr": value,
            "donatedOn": (today - timedelta(days=days_ago)).isoformat(),
            "notes": why,
            "ingredients": lines,
        }
        try:
            made = counter.post("/api/v1/donations", payload)
            args.state.put(key, made["id"])
            what = ", ".join(f"{q} {u} of {n}" for n, q, u in items)
            tally.made("gift in kind", f"{'someone anonymous' if anonymous else who} — {what}")
        except ApiError as e:
            tally.problem(f"gift in kind from {who}: {e}")

    # ---- one voided ---------------------------------------------------------
    # A gift recorded against the wrong person, struck from the ledger. It stays visible and
    # marked rather than disappearing, and the in-kind stock it added is reversed with it.
    step("striking one out")
    voided = f"{PHASE}.voided"
    if args.state.has(voided):
        tally.kept("void")
    else:
        ledger = admin.get("/api/v1/donations/ledger")
        rows = ledger if isinstance(ledger, list) else ledger.get("rows", [])
        target = next((r for r in rows if not r.get("voided") and r.get("category") == "ONE_TIME"),
                      None)
        if not target:
            tally.skip("void", "nothing suitable to strike out")
        else:
            try:
                admin.post(f"/api/v1/donations/{target['id']}/void", {
                    "reason": "Recorded against the wrong donor at the counter. "
                              "Re-entered under the right name.",
                })
                args.state.put(voided, True)
                tally.made("void", f"Rs {target.get('amountInr')} — wrong donor, re-entered")
            except ApiError as e:
                tally.problem(f"voiding a donation: {e}")

    # ---- what the ledger shows ----------------------------------------------
    step("the ledger")
    ledger = admin.get("/api/v1/donations/ledger")
    rows = ledger if isinstance(ledger, list) else ledger.get("rows", [])
    money = sum(float(r.get("amountInr") or 0) for r in rows if not r.get("voided"))
    by_category: dict[str, int] = {}
    for row in rows:
        by_category[row.get("category") or "?"] = by_category.get(row.get("category") or "?", 0) + 1
    info(f"{len(rows)} entries, Rs {money:,.0f} received")
    for category, n in sorted(by_category.items()):
        info(f"  {category:12} {n}")

    wishlist = admin.get("/api/v1/wishlist")
    for item in sorted(wishlist, key=lambda w: w.get("sortOrder", 0)):
        target = float(item["priceInr"]) * int(item["quantityWanted"])
        paid = float(item.get("paidInr") or 0)
        info(f"  {item['title'][:42]:44} Rs {paid:>9,.0f} of {target:>9,.0f}  {item['status']}")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
