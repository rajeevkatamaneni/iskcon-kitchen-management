"""The arguments every phase takes, so they are the same everywhere."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Optional

from .state import State

# The simulation window. Four weeks ending the Saturday Rajeev demos.
#
# Not the three months the brief asked for, and not the eight weeks that replaced them: Rajeev cut
# it on 2026-09-19 — "ok do 4 weeks then. I need it sooner than 8 hours." And it could never have
# begun before 1 August whatever the budget, because the calendar is precomputed forward only and
# GET /api/v1/calendar/2026-06-27 answers 204. See tools/seed/README.md.
WINDOW_FROM = date(2026, 8, 29)
WINDOW_TO = date(2026, 9, 26)

# Two blocks, because the brief asks for planning "in 15 day blocks".
BLOCKS = [(date(2026, 8, 29), date(2026, 9, 12)), (date(2026, 9, 13), date(2026, 9, 26))]

# Who does what. The role matters: a seeding run that did everything as one superuser would prove
# nothing about permissions, and the brief asks for each act to be done by the person who does it.
TEMPLE_ADMIN = "ikms.temple-admin.1@trading4good.org"
KITCHEN_STAFF = [f"ikms.kitchen-staff.{n}@trading4good.org" for n in (1, 2, 3, 4)]

# Who runs the kitchen is resolved at run time, not assumed.
#
# Locally `ikms.kitchen-staff.5` holds KITCHEN_MANAGER; on staging **the same account is plain
# KITCHEN_STAFF and no account holds KITCHEN_MANAGER at all**. Hardcoding it meant phase 10's
# approvals would have hit 403 on staging and nowhere else, because approving an ingredient
# request is the one thing KITCHEN_STAFF cannot do that KITCHEN_MANAGER can.
#
# So: try the candidates, take the first that really holds the role, and fall back to the Temple
# Admin — who can do everything — saying so rather than failing quietly. The same scripts then run
# against either environment, which is the whole point of them taking the API as an argument.
KITCHEN_MANAGER_CANDIDATES = ["ikms.kitchen-staff.5@trading4good.org"] + KITCHEN_STAFF

# What the manager is needed FOR, so a fallback is only accepted when it can really do the job.
# APPROVE_INGREDIENT_REQUESTS is the deciding one: TEMPLE_ADMIN and KITCHEN_MANAGER have it,
# KITCHEN_STAFF does not.
_MANAGER_ROLES = ("KITCHEN_MANAGER", "TEMPLE_ADMIN")
VOLUNTEERS = [f"ikms.volunteer.{n}@trading4good.org" for n in range(1, 6)]
DONORS = [f"ikms.donor.{n}@trading4good.org" for n in (1, 2)]


@dataclass
class SeedArgs:
    api: str
    tenant: str
    state: State
    dry_run: bool
    verbose: bool
    window_from: date
    window_to: date

    def dates(self) -> list[date]:
        """Every day in the window, in order."""
        from datetime import timedelta
        out, day = [], self.window_from
        while day <= self.window_to:
            out.append(day)
            day += timedelta(days=1)
        return out


def parse_args(phase: str, extra: Optional[callable] = None) -> SeedArgs:
    parser = argparse.ArgumentParser(
        prog=phase,
        description=f"{phase} — part of the temple simulation. See tools/seed/README.md.")
    parser.add_argument("--api", required=True,
                        help="API base URL, e.g. http://localhost:8091")
    parser.add_argument("--tenant", required=True,
                        help="the temple's id")
    parser.add_argument("--state-dir", default=str(Path(__file__).resolve().parent.parent / ".state"),
                        help="where the run ledger lives (default tools/seed/.state)")
    parser.add_argument("--dry-run", action="store_true",
                        help="say what would happen and write nothing")
    parser.add_argument("--forget", action="store_true",
                        help="drop this phase's ledger entries first, so it builds again")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--from", dest="window_from", default=WINDOW_FROM.isoformat())
    parser.add_argument("--to", dest="window_to", default=WINDOW_TO.isoformat())
    if extra:
        extra(parser)
    ns = parser.parse_args()

    state = State(Path(ns.state_dir), ns.api, ns.tenant)
    if ns.forget:
        dropped = state.forget(f"{phase}.")
        print(f"forgot {dropped} ledger entries for {phase}")

    args = SeedArgs(
        api=ns.api, tenant=ns.tenant, state=state, dry_run=ns.dry_run, verbose=ns.verbose,
        window_from=date.fromisoformat(ns.window_from), window_to=date.fromisoformat(ns.window_to))
    args.raw = ns  # phases with their own flags read them from here
    return args


_resolved_manager: dict[str, str] = {}


def kitchen_manager(api: str, tenant: str, *, needs_approval: bool = True,
                    needs_planner: bool = False) -> str:
    """
    The email of whoever runs the kitchen here, chosen for what the caller is about to do.

    `needs_approval` — the caller will approve or deny an ingredient request, which only a real
    KITCHEN_MANAGER or the Temple Admin can do.

    `needs_planner` — the caller will touch `/api/v1/meals`, `/meal-plans`, `/meal-crew` or
    `/job-cards`. Having the role is not enough for those: `PlannerKitchenGuard` lets a
    TEMPLE_ADMIN through always, and anyone else **only if their own kitchen has
    `uses_meal_planner`**. Staging has a kitchen-staff account in a restaurant kitchen that does
    not plan, and picking it got a 403 KMS-400183 on the very first call of phase 05. `whoami`
    answers this directly with `canPlanMeals`, so it is asked rather than guessed at.
    """
    from .api import sign_in
    from .log import note

    key = f"{api}|{tenant}|{needs_approval}|{needs_planner}"
    if key in _resolved_manager:
        return _resolved_manager[key]

    def suitable(session) -> bool:
        if needs_approval and session.whoami.get("role") not in _MANAGER_ROLES:
            return False
        if needs_planner and not session.whoami.get("canPlanMeals"):
            return False
        return True

    best_role = None
    for email in KITCHEN_MANAGER_CANDIDATES:
        try:
            session = sign_in(api, email, tenant)
        except Exception:
            continue
        if suitable(session):
            if session.whoami.get("role") in _MANAGER_ROLES:
                _resolved_manager[key] = email
                return email
            if best_role is None:
                best_role = email

    if best_role:
        what = "plan meals" if needs_planner else "do what this phase needs"
        note(f"no KITCHEN_MANAGER on this temple; using {best_role.split('@')[0]} "
             f"(KITCHEN_STAFF), who can {what}")
        _resolved_manager[key] = best_role
        return best_role

    why = ("approving and denying ingredient requests" if needs_approval
           else "the meal planner" if needs_planner else "this phase")
    note(f"no kitchen account here is suitable for {why}; the Temple Admin is standing in")
    _resolved_manager[key] = TEMPLE_ADMIN
    return TEMPLE_ADMIN
