"""Printing. Every phase says what it created, in plain words, as it goes."""

from __future__ import annotations

import sys
import time
from collections import Counter

_START = time.monotonic()


def _stamp() -> str:
    return f"{time.monotonic() - _START:6.1f}s"


def step(text: str) -> None:
    """A heading for a stage of the phase."""
    print(f"\n[{_stamp()}] == {text}", flush=True)


def info(text: str) -> None:
    print(f"[{_stamp()}]    {text}", flush=True)


def note(text: str) -> None:
    """Something worth reading but not a thing that was created."""
    print(f"[{_stamp()}]  · {text}", flush=True)


def warn(text: str) -> None:
    print(f"[{_stamp()}]  ! {text}", file=sys.stderr, flush=True)


def fail(text: str) -> None:
    print(f"[{_stamp()}]  X {text}", file=sys.stderr, flush=True)


class Tally:
    """Counts what a phase made, so the run ends with a list a person can check.

    Reused rows (idempotent re-run) are counted apart from new ones, because
    "created 0, reused 412" on a second run is the proof that a script is re-runnable.
    """

    def __init__(self, phase: str) -> None:
        self.phase = phase
        self.created: Counter[str] = Counter()
        self.reused: Counter[str] = Counter()
        self.skipped: Counter[str] = Counter()
        self.problems: list[str] = []

    def made(self, what: str, detail: str = "", n: int = 1) -> None:
        self.created[what] += n
        if detail:
            info(f"created {what}: {detail}")

    def kept(self, what: str, detail: str = "", n: int = 1) -> None:
        self.reused[what] += n
        if detail:
            note(f"already there, {what}: {detail}")

    def skip(self, what: str, why: str = "", n: int = 1) -> None:
        self.skipped[what] += n
        if why:
            note(f"skipped {what}: {why}")

    def problem(self, text: str) -> None:
        self.problems.append(text)
        warn(text)

    def report(self) -> int:
        """Print the summary. Returns the exit code to use."""
        print(f"\n===== {self.phase} =====", flush=True)
        if not self.created and not self.reused and not self.skipped:
            print("nothing to do", flush=True)
        for label, counter in (("created", self.created), ("reused", self.reused), ("skipped", self.skipped)):
            for what, n in sorted(counter.items()):
                print(f"  {label:8} {n:5d}  {what}", flush=True)
        if self.problems:
            print(f"\n  {len(self.problems)} problem(s):", flush=True)
            for p in self.problems:
                print(f"    - {p}", flush=True)
            return 1
        return 0
