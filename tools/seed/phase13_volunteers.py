#!/usr/bin/env python3
"""
Phase 13 — volunteers signing up for shifts, and who turned up.

Rajeev's brief: "also raise volunteer shifts while meal planning and simulate volunteer signing up
or field shifts under field shift shifts that are filled just enough and the combination of
everything."

**The shifts themselves were created in phase 05**, not here, and that is forced by the
application: `POST /api/v1/shifts` cannot attach a shift to a meal. A meal's shift goes inside the
meal's own save request and is committed in the same transaction, so the only place it can be
created is when the meal is. What is left for this phase is the part volunteers do.

So it fills them to different depths, because a coordinator's screen is only useful when it shows
all three:

    full          every place taken, and the next person goes on the waiting list
    part-filled   some places taken, still asking
    empty         nobody has signed up at all

**A volunteer signs themselves up.** `POST /api/v1/shifts/{id}/signup` takes no body and acts on
the caller, so this phase signs in as each volunteer in turn rather than adding them as an admin.
That is the only way the rows carry the right person.

**A shift that has already started cannot be signed up for** — `KMS-400059`, and rightly: nobody
volunteers for last Tuesday. That has a consequence this simulation cannot get around. Most of the
window is in the past, so most of its shifts are closed to sign-up, and **the past shifts stay
empty**. Only the ones still to come can be filled.

There is no API way round it, and there should not be. If the demo needs a history of volunteers
who turned up, the sign-up rows have to be written the way the back-dating is — directly, in one
named script — and nobody has asked for that.

**Attendance is only recorded for shifts that have already happened**, and only where somebody
signed up while they still could. Attendance is tri-state — present, absent, or not yet marked —
so an unmarked future shift is the correct state, not a gap.

    python3 tools/seed/phase13_volunteers.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import DONORS, TEMPLE_ADMIN, VOLUNTEERS, kitchen_manager  # noqa: E402

PHASE = "phase13"

# Standalone shifts for the coming week — the ones volunteers can actually still sign up for.
#
# Phase 05 puts a shift on every festival and every Sunday lunch, but in this window all of those
# have already happened, and the application will not take a sign-up for a shift that has started
# (KMS-400059). So the roster would be a page of empty past shifts and nothing else. These are the
# calls a coordinator really posts: next week's work, open for people to put their names to.
#
# (days from today, title, start, end, capacity, where, what it is)
UPCOMING = [
    # Called "morning" until 2026-09-20, which read as a contradiction on the screen: the hours
    # are the lunch sitting and the description says so in its first four words.
    (1, "Lunch prasadam service", "10:00:00", "14:00:00", 8, "Prasadam hall",
     "Serving the lunch queue and clearing afterwards."),
    (2, "Vegetable cutting", "06:30:00", "09:30:00", 6, "Main Kitchen, prep bay",
     "Cutting for the day's sabjis. No cooking experience needed."),
    (3, "Evening prasadam service", "18:30:00", "21:30:00", 10, "Prasadam hall",
     "Dinner service and washing up."),
    (4, "Store stock-take", "09:00:00", "13:00:00", 4, "Temple store",
     "Counting the dry store against the book with the store manager."),
    (5, "Visvarupa Mahotsava kitchen help", "07:00:00", "15:00:00", 16, "Main Kitchen",
     "The big one. Cutting, cooking, serving and cleaning through the day."),
    (6, "Deity flower arranging", "05:30:00", "08:00:00", 5, "Deity Kitchen",
     "Garlands and altar flowers for the morning."),
]

# How full each shift ends up, in rotation. A fraction of its capacity; 0 means nobody came
# forward, and > 1 means it filled and somebody went on the waiting list.
FILL = [1.0, 0.5, 0.0, 1.2, 0.75, 0.25, 1.0, 0.0]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("phase 13 — volunteers")

    manager = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False), args.tenant)
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    people = VOLUNTEERS + DONORS
    volunteers = []
    for email in people:
        try:
            volunteers.append(sign_in(args.api, email, args.tenant))
        except Exception as e:
            tally.problem(f"{email} cannot sign in: {e}")
    if not volunteers:
        tally.problem("no volunteer could sign in; nobody can sign up for anything")
        return tally.report()
    info(f"{len(volunteers)} volunteer(s) available")

    shifts = manager.get("/api/v1/shifts")
    if isinstance(shifts, dict):
        shifts = shifts.get("shifts", [])
    if not shifts:
        tally.problem("no shifts exist — phase 05 creates them with the meals it plans")
        return tally.report()
    info(f"{len(shifts)} shift(s) on the roster")

    today = date.today()

    # ---- the shifts people can still put their names to ---------------------
    step("posting next week's shifts")
    for days, title, start, end, capacity, where, what in UPCOMING:
        when = (today + timedelta(days=days)).isoformat()
        key = f"{PHASE}.shift.{when}.{title}"
        if args.state.has(key):
            tally.kept("shift posted", title)
            continue
        if any(x.get("shiftDate") == when and x.get("title") == title for x in shifts):
            tally.kept("shift posted", title)
            continue
        try:
            made = manager.post("/api/v1/shifts", {
                "title": title, "description": what, "shiftDate": when,
                "startTime": start, "endTime": end, "location": where,
                "capacity": capacity, "reminderOffsetsMinutes": [1440, 120],
            })
            args.state.put(key, made["id"])
            tally.made("shift posted", f"{when} {title} — {capacity} places, {where}")
        except ApiError as e:
            tally.problem(f"posting {title}: {e}")

    shifts = manager.get("/api/v1/shifts")
    if isinstance(shifts, dict):
        shifts = shifts.get("shifts", [])

    open_to_signup = [s for s in shifts if (s.get("shiftDate") or "") >= today.isoformat()]
    closed = len(shifts) - len(open_to_signup)
    if closed:
        note(f"{closed} shift(s) have already started and cannot be signed up for (KMS-400059); "
             f"{len(open_to_signup)} still can be")

    step("signing up, on the shifts that have not started")
    for index, shift in enumerate(sorted(open_to_signup,
                                         key=lambda s: (s.get("shiftDate") or "", s["id"]))):
        capacity = int(shift.get("capacity") or 0)
        if capacity <= 0:
            continue
        share = FILL[index % len(FILL)]
        wanted = int(round(capacity * share))

        if wanted == 0:
            tally.made("shift left empty",
                       f"{shift.get('shiftDate')} {shift.get('title')} — nobody came forward")
            continue

        taken = 0
        waitlisted = 0
        for position in range(min(wanted, len(volunteers))):
            who = volunteers[(index + position) % len(volunteers)]
            key = f"{PHASE}.signup.{shift['id']}.{who.email}"
            if args.state.has(key):
                taken += 1
                continue
            try:
                who.post(f"/api/v1/shifts/{shift['id']}/signup")
                args.state.put(key, True)
                taken += 1
            except ApiError as e:
                if e.code == "KMS-400061":
                    # Full. The next person goes on the waiting list, which is the real behaviour
                    # and the only way a waitlist row ever exists.
                    try:
                        who.post(f"/api/v1/shifts/{shift['id']}/waitlist")
                        args.state.put(key, "waitlist")
                        waitlisted += 1
                    except ApiError:
                        pass
                elif e.code in ("KMS-400062", "KMS-400063"):
                    args.state.put(key, True)   # already signed up
                    taken += 1
                elif e.code == "KMS-400059":
                    # It started while this phase was running, or the roster moved under us.
                    break
                else:
                    tally.problem(f"{who.email} on {shift.get('title')}: {e}")

        how = "filled" if taken >= capacity else f"{taken} of {capacity}"
        detail = f"{shift.get('shiftDate')} {shift.get('title')} — {how}"
        if waitlisted:
            detail += f", {waitlisted} on the waiting list"
        tally.made("shift signed up", detail)

    # ---- who actually turned up -------------------------------------------
    step("marking who turned up, on the shifts that have already happened")
    for shift in shifts:
        when = shift.get("shiftDate")
        if not when or when >= today.isoformat():
            continue
        key = f"{PHASE}.attendance.{shift['id']}"
        if args.state.has(key):
            tally.kept("attendance")
            continue

        detail = manager.get(f"/api/v1/shifts/{shift['id']}")
        signups = detail.get("signups") or []
        if not signups:
            continue

        # Most turn up; one in five does not. A roster where everybody always came would tell a
        # coordinator nothing.
        marks = []
        for position, signup in enumerate(signups):
            user_id = signup.get("userId") or signup.get("volunteerUserId")
            if not user_id:
                continue
            marks.append({"userId": user_id, "attended": position % 5 != 3})

        if not marks:
            continue
        try:
            manager.post(f"/api/v1/shifts/{shift['id']}/attendance", {"marks": marks})
            args.state.put(key, True)
            came = sum(1 for m in marks if m["attended"])
            tally.made("attendance",
                       f"{when} {shift.get('title')} — {came} of {len(marks)} turned up")
        except ApiError as e:
            tally.problem(f"attendance for {shift.get('title')}: {e}")

    # ---- what the coordinator sees ----------------------------------------
    step("the roster")
    shifts = manager.get("/api/v1/shifts")
    if isinstance(shifts, dict):
        shifts = shifts.get("shifts", [])
    full = part = empty = 0
    for shift in shifts:
        capacity = int(shift.get("capacity") or 0)
        signed = int(shift.get("signedUpCount") or 0)
        if signed == 0:
            empty += 1
        elif signed >= capacity:
            full += 1
        else:
            part += 1
    info(f"{full} full, {part} part-filled, {empty} with nobody signed up")

    for state, n in (("full", full), ("part-filled", part), ("empty", empty)):
        if n == 0:
            note(f"no shift is {state}; the coordinator's screen will not show that case")
    if closed:
        note(f"{closed} of those are empty only because they had already started — the "
             f"application will not take a sign-up for a shift in the past, so a seeded history "
             f"of volunteers is not something the API can produce")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
