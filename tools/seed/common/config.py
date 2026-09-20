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
KITCHEN_MANAGER = "ikms.kitchen-staff.5@trading4good.org"   # the KITCHEN_MANAGER row, oddly named
KITCHEN_STAFF = [f"ikms.kitchen-staff.{n}@trading4good.org" for n in (1, 2, 3, 4)]
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
