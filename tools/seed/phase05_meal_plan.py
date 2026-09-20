#!/usr/bin/env python3
"""
Phase 05 — the meal plan.

Rajeev's brief: "Then start meal planning. Create meal plans in 15 day blocks. Be sure to pick a
variety of meal plans like every day we have breakfast lunch and dinner obviously so pick festival
events pick outside events pick events in the temple. Pick delivery outside events pick outside
events where they will come and pick up all combinations basically."

So: three meals every day, and on top of them the festivals on their real dates, some temple
events, and outside events both delivered and collected.

**How much of each dish.** Every recipe carries what one person eats — 0.3 L of Chitranna, 3
chapatis — so the amount cooked is that figure times the head count, in the recipe's own yield
unit. That is how a temple actually decides, and it means the shopping list downstream is a real
quantity rather than a made-up one. The few recipes with no per-head figure are the pickles and
the Mysore Pak, which nobody serves by the head; those get a flat batch.

**Ekadashi is not a normal day.** On the two Ekadashis in the window every dish comes from the
Ekadashi category — sago, potato, sweet potato, groundnut, rock salt — and no grain or pulse is
planned at all. That is the point of the flag phase 01 set, and planning a grain there would have
to be acknowledged explicitly.

**The volunteer shift is saved with the meal, not separately.** `POST /api/v1/shifts` cannot
attach one to a meal; the draft goes inside the meal's own request and is committed in the same
transaction. So the shifts are created here and phase 13 does the signing up.

    python3 tools/seed/phase05_meal_plan.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import random
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN, kitchen_manager  # noqa: E402

PHASE = "phase05"

# A fixed seed, so two runs of this script plan the same menu. A simulation that changed every
# time it was rebuilt would be impossible to talk about ("the Tuesday with the Puliyogare").
SEED = 20260829


# How long before a meal is ready the crew turns up, and how long after it they are still there.
# Cooking and laying out the hall take the first; serving and clearing take the second.
SHIFT_BEFORE = timedelta(hours=2, minutes=30)
SHIFT_AFTER = timedelta(hours=2)

# Where a meal kind has no ready time of its own — an event, a deity offering — the shift falls
# back to the middle of the day rather than to a number typed twice.
FALLBACK_READY = time(12, 0)


def shift_hours(kind: dict) -> tuple[str, str]:
    """
    The hours of the volunteer shift on a meal, taken from when that meal is ready.

    They used to be hard-coded: everything but dinner ran 10:00 to 14:00. That put a **breakfast
    service at ten in the morning**, and on a festival day it put the breakfast, the lunch and the
    feast in the identical four hours — three services nobody could work more than one of, which
    is also what made the roster read as though nobody helps.

    The application already knows the answer. Every meal kind carries a `defaultReadyTime`:
    breakfast 07:30, lunch 12:00, the festival feast 12:30, dinner 19:30. A shift is that time with
    the cooking before it and the clearing after it, so breakfast now starts at five in the morning,
    which is when it really starts.
    """
    raw = (kind.get("defaultReadyTime") or "").strip()
    try:
        ready = time.fromisoformat(raw) if raw else FALLBACK_READY
    except ValueError:
        ready = FALLBACK_READY
    # Any date will do; only the clock arithmetic matters, and datetime is the only thing that
    # knows how to take two and a half hours off a time without going negative.
    middle = datetime.combine(date(2000, 1, 1), ready)
    return ((middle - SHIFT_BEFORE).time().isoformat(),
            (middle + SHIFT_AFTER).time().isoformat())


def heads(day: date, festival: str | None) -> tuple[int, int, int]:
    """Adults, children, seniors. A temple is busier at the weekend and full at a festival."""
    if festival:
        return (1400, 380, 220) if "Janmastami" in festival else (620, 160, 110)
    if day.weekday() == 6:      # Sunday
        return 430, 120, 90
    if day.weekday() == 5:      # Saturday
        return 330, 90, 70
    return 240, 55, 45


def amount(recipe: dict, people: int, share: float = 1.0) -> float:
    """
    How much to cook, in the recipe's own yield unit.

    `share` is for a dish that is one of two of its kind on the same meal — two sabjis means half
    as much of each, not twice as much food.
    """
    per_head = recipe.get("perHeadQty")
    if per_head:
        raw = float(per_head) * people * share
    else:
        # No per-head figure: the pickles and the Mysore Pak. A flat batch scaled to the crowd.
        raw = float(recipe["baseYieldQty"]) * share * (1.0 if people < 500 else 2.0)
    raw = max(raw, 0.5)
    return round(min(raw, 50000), 2)   # 50,000 is the ceiling the planner refuses beyond


class Menu:
    """Picks dishes without repeating one two days running."""

    def __init__(self, recipes: list[dict]) -> None:
        self.by_category: dict[str, list[dict]] = {}
        for r in recipes:
            self.by_category.setdefault(r["categoryName"], []).append(r)
        for rows in self.by_category.values():
            rows.sort(key=lambda r: r["name"])
        self.rng = random.Random(SEED)
        self.recent: dict[str, list[str]] = {}

    def pick(self, category: str, n: int = 1) -> list[dict]:
        rows = self.by_category.get(category) or []
        if not rows:
            return []
        recent = self.recent.setdefault(category, [])
        fresh = [r for r in rows if r["name"] not in recent] or rows
        chosen = self.rng.sample(fresh, min(n, len(fresh)))
        for r in chosen:
            recent.append(r["name"])
        del recent[:-max(2, len(rows) // 2)]
        return chosen


def dishes_for(menu: Menu, kind: str, people: int, ekadashi: bool, festival: bool) -> list:
    """The dishes on one meal, as (recipe, share) pairs."""
    if ekadashi:
        # Nothing with a grain or a pulse in it.
        if kind == "Breakfast":
            return [(r, 1.0) for r in menu.pick("Ekadashi", 1)]
        return [(r, 1.0) for r in menu.pick("Ekadashi", 2)]

    if kind == "Breakfast":
        out = [(r, 1.0) for r in menu.pick("Breakfast", 1)]
        if festival:
            out += [(r, 1.0) for r in menu.pick("Sweets", 1)]
        return out

    if kind == "Lunch":
        out = [(r, 1.0) for r in menu.pick("Rice", 1)]
        out += [(r, 1.0) for r in menu.pick("Dal", 1)]
        out += [(r, 0.5) for r in menu.pick("Sabji", 2)]
        out += [(r, 1.0) for r in menu.pick("Roti", 1)]
        return out

    if kind == "Dinner":
        out = [(r, 1.0) for r in menu.pick("Khichadi", 1)]
        out += [(r, 1.0) for r in menu.pick("Sabji", 1)]
        return out

    if kind == "Festival feast":
        out = [(r, 0.5) for r in menu.pick("Rice", 2)]
        out += [(r, 1.0) for r in menu.pick("Dal", 1)]
        out += [(r, 0.5) for r in menu.pick("Sabji", 2)]
        out += [(r, 1.0) for r in menu.pick("Roti", 1)]
        out += [(r, 0.5) for r in menu.pick("Sweets", 2)]
        out += [(r, 1.0) for r in menu.pick("Jam & pickles", 1)]
        return out

    # An event: one rice, one sabji, one sweet.
    out = [(r, 1.0) for r in menu.pick("Rice", 1)]
    out += [(r, 1.0) for r in menu.pick("Sabji", 1)]
    out += [(r, 1.0) for r in menu.pick("Sweets", 1)]
    return out


# The events, on real days in the window. Two in-house, one collected, two delivered — the
# combinations Rajeev asked for.
EVENTS = [
    {"date": date(2026, 9, 1), "name": "Bhagavad-gita study circle", "outside": False,
     "people": 70, "purpose": "Monthly study circle supper for the congregation"},
    {"date": date(2026, 9, 6), "name": "Whitefield namahatta programme", "outside": True,
     "handover": "DELIVERY", "people": 160,
     "contact": ("Ramesh Iyer", "+919845031001"),
     "address": "Prestige Shantiniketan Clubhouse, Whitefield, Bengaluru 560048",
     "sub": "Clubhouse, second floor", "eat_at": "19:30", "travel": 55,
     "purpose": "Prasadam for the Whitefield namahatta evening programme"},
    {"date": date(2026, 9, 12), "name": "Ramanujan college youth retreat", "outside": True,
     "handover": "PICKUP", "people": 120,
     "contact": ("Sandeep Nair", "+919845031002"),
     "purpose": "Retreat lunch, collected from the temple gate at 11am"},
    {"date": date(2026, 9, 17), "name": "Donor appreciation evening", "outside": False,
     "people": 95, "purpose": "Supper for the temple's regular donors"},
    {"date": date(2026, 9, 24), "name": "Koramangala house programme", "outside": True,
     "handover": "DELIVERY", "people": 85,
     "contact": ("Anita Prabhu", "+919845031003"),
     "address": "212 5th Block Koramangala, Bengaluru 560095",
     "sub": "Gate B, ring the bell", "eat_at": "20:00", "travel": 40,
     "purpose": "House programme dinner in Koramangala"},
]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 05 — the meal plan")

    # The Kitchen Manager plans the meals: it is their job, and it exercises the planner's
    # kitchen guard rather than going round it as the Temple Admin.
    planner = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False, needs_planner=True), args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    kinds = {k["name"]: k for k in planner.get("/api/v1/meal-kinds")}
    recipes = admin.get("/api/v1/recipes")
    if not recipes:
        tally.problem("the temple has no recipes — run phase 02 first")
        return tally.report()

    full = {}
    for r in recipes:
        full[r["name"]] = r
    menu = Menu(list(full.values()))

    calendar = {d["date"]: d for d in planner.get(
        f"/api/v1/calendar?from={args.window_from}&to={args.window_to}")}
    occasions: dict[str, str] = {}
    for o in planner.get(
            f"/api/v1/occasions/resolved?from={args.window_from}&to={args.window_to}"):
        occasions[o["date"]] = o["name"]

    info(f"{len(recipes)} recipes, {len(occasions)} festival(s), "
         f"{sum(1 for d in calendar.values() if d.get('isEkadashi'))} Ekadashi in the window")

    # ---- what is already planned, read from the server, not from the ledger ----
    #
    # This phase is the one that is genuinely dangerous to run twice without its ledger, and the
    # reason is in the endpoint's own documentation: "Planning a meal that already exists reuses
    # it… The dishes sent here are added to it." So a second run does not fail and does not make
    # a duplicate meal — it quietly doubles the dishes on every meal, and the only sign is that
    # every lunch now has ten dishes instead of five.
    #
    # The ledger normally prevents that, but a ledger is a file on one machine. Run the same
    # phase from a different checkout, or after a `--forget`, and it is gone while the meals are
    # not. So the real check is against the application: a meal is identified by its date, its
    # kind and its event name, which is exactly how the saver decides two meals are the same one.
    existing = planner.get(f"/api/v1/meals?from={args.window_from}&to={args.window_to}")
    if isinstance(existing, dict):
        existing = existing.get("meals", [])
    already = {
        (m["planDate"], m["mealKindId"], (m.get("eventName") or "").strip().lower())
        for m in existing
    }
    if already:
        note(f"{len(already)} meal(s) already planned in this window; they will be left alone")

    events_by_date: dict[date, dict] = {e["date"]: e for e in EVENTS}

    step(f"planning {args.window_from} to {args.window_to}")
    for day in args.dates():
        iso = day.isoformat()
        cal = calendar.get(iso, {})
        ekadashi = bool(cal.get("isEkadashi"))
        festival = occasions.get(iso)

        adults, children, seniors = heads(day, festival)
        people = adults + children + seniors

        todays = ["Breakfast", "Lunch", "Dinner"]
        if festival and "Festival feast" in kinds:
            todays.append("Festival feast")

        for kind_name in todays:
            kind = kinds.get(kind_name)
            if not kind:
                continue
            key = f"{PHASE}.meal.{iso}.{kind_name}"
            if args.state.has(key) or (iso, kind["id"], "") in already:
                tally.kept("meal")
                continue

            chosen = dishes_for(menu, kind_name, people, ekadashi, bool(festival))
            if not chosen:
                tally.problem(f"{iso} {kind_name}: no recipe to cook")
                continue

            payload = {
                "planDate": iso,
                "mealKindId": kind["id"],
                "adults": adults, "children": children, "seniors": seniors,
                "dishes": [{"recipeId": r["id"], "targetYield": amount(r, people, share)}
                           for r, share in chosen],
            }
            if kind.get("needsOccasion"):
                payload["occasionName"] = festival
            if ekadashi:
                payload["kitchenNotes"] = (
                    "Ekadashi. No grains and no pulses. Rock salt only.")

            # A shift on the meals that need hands: every festival, and Sunday lunch.
            if festival or (kind_name == "Lunch" and day.weekday() == 6):
                start, end = shift_hours(kind)
                payload["volunteerShift"] = {
                    "title": f"{festival or 'Sunday'} {kind_name.lower()} service",
                    "description": "Serving prasadam and clearing the hall afterwards.",
                    "startTime": start,
                    "endTime": end,
                    "location": "Prasadam hall",
                    "capacity": 12 if festival else 6,
                    "reminderOffsetsMinutes": [1440, 120],
                }

            try:
                made = planner.post("/api/v1/meals", payload)
                args.state.put(key, made["id"])
                what = ", ".join(r["name"] for r, _ in chosen)
                flag = " [Ekadashi]" if ekadashi else (f" [{festival}]" if festival else "")
                tally.made("meal", f"{iso} {kind_name:15}{flag} {people:>5} people — {what}")
            except ApiError as e:
                tally.problem(f"{iso} {kind_name}: {e}")

        # ---- an event on top of the ordinary day --------------------------
        event = events_by_date.get(day)
        if not event:
            continue
        key = f"{PHASE}.event.{iso}"
        kind = next((k for k in kinds.values() if k.get("isEvent")), None)
        if not kind:
            tally.problem("no meal kind flagged as an event")
            continue
        if args.state.has(key) or (iso, kind["id"], event["name"].strip().lower()) in already:
            tally.kept("event")
            continue

        chosen = dishes_for(menu, "Event", event["people"], False, False)
        payload = {
            "planDate": iso,
            "mealKindId": kind["id"],
            "readyBy": "11:00:00" if event.get("handover") == "PICKUP" else "17:30:00",
            "eventName": event["name"],
            "isOutside": event["outside"],
            "purpose": event["purpose"],
            "adults": event["people"], "children": 0, "seniors": 0,
            "dishes": [{"recipeId": r["id"], "targetYield": amount(r, event["people"], share)}
                       for r, share in chosen],
        }
        if event["outside"]:
            payload["handover"] = event["handover"]
            payload["contactName"], payload["contactPhone"] = event["contact"]
            if event["handover"] == "DELIVERY":
                payload["deliveryAddress"] = event["address"]
                payload["deliverySubLocation"] = event.get("sub")
                payload["guestsEatAt"] = event["eat_at"]
                payload["travelMinutes"] = event["travel"]
                payload["travelMinutesManual"] = True

        try:
            made = planner.post("/api/v1/meals", payload)
            args.state.put(key, made["id"])
            how = ("delivered" if event.get("handover") == "DELIVERY"
                   else "collected" if event["outside"] else "in the temple")
            tally.made("event", f"{iso} {event['name']} — {how}, {event['people']} people")
        except ApiError as e:
            tally.problem(f"{iso} event {event['name']}: {e}")

    # ---- what the planner now shows ---------------------------------------
    step("the plan as the application holds it")
    for start, end in ((args.window_from, args.window_to),):
        meals = planner.get(f"/api/v1/meals?from={start}&to={end}")
        if isinstance(meals, dict):
            meals = meals.get("meals", [])
        info(f"{len(meals)} meal(s) planned between {start} and {end}")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
