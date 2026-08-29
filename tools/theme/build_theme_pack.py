#!/usr/bin/env python3
"""
Builds a theme pack: twenty-three colours that work as one unit and can be
proven to.

Why this is a script and not a designer picking swatches
--------------------------------------------------------
A palette for this application is not eight colours on a marketing page. It is
twenty-three roles that appear in about two thousand places, and roughly forty
pairings among them that a person has to be able to read. Choosing those by eye
means checking forty ratios by hand for every pack, fifteen times over, and the
two contrast failures already in this codebase's history — ink-muted in August
and the focus ring found on 2026-08-28 — were both introduced by somebody who
was picking a colour they liked and did not think to check the pairing.

So the lightness of every role is *solved* against the contrast it has to
clear, rather than chosen and then tested. The freshness comes from the hue and
the chroma; the safety comes from the solve. That idea, the OKLCH machinery and
the gamut mapping are all tastemaker's — this only knows what pairings this
particular interface makes, which is the part tastemaker cannot know.

Usage:
    python3 tools/theme/build_theme_pack.py --slug harbour-blue \\
        --name "Harbour blue" --family BALANCED --hue 245 \\
        --description "Deep harbour blue on white."

    # Hold a pack this script did not build to the same contract:
    python3 tools/theme/build_theme_pack.py --check - < pack.json

Prints the palette, the full contrast report, and the entry to paste into
frontend/lib/theme-packs.ts. Exits non-zero if any required pairing fails, so
this cannot produce a pack it cannot defend.
"""

import argparse
import json
import os
import sys

# tastemaker does the colour science: OKLCH conversion, gamut mapping by chroma
# reduction, WCAG relative luminance. Imported rather than vendored so that a
# correction upstream reaches us, and located by env var so a machine that
# installed the skill elsewhere can still run this.
SKILL = os.environ.get(
    "TASTEMAKER_SCRIPTS",
    os.path.expanduser("~/.claude/skills/tastemaker/scripts"),
)
if not os.path.isdir(SKILL):
    sys.exit(
        f"tastemaker's scripts are not at {SKILL}.\n"
        "Install the skill (github.com/codeswithroh/tastemaker, skills/tastemaker)\n"
        "or point TASTEMAKER_SCRIPTS at them."
    )
sys.path.insert(0, SKILL)

from check_contrast import ratio  # noqa: E402
from generate_palette import oklch_to_hex  # noqa: E402


# ---------------------------------------------------------------------------
# The contract
# ---------------------------------------------------------------------------

# The twenty-three roles, in the order docs/DESIGN_SYSTEM.md §2 introduces them.
# The same list lives in frontend/lib/theme.ts, and the two have to agree —
# which is what __tests__/theme-contract.test.ts checks, over every pack in the
# catalogue, on every commit.
TOKENS = [
    "canvas", "raised", "sunken",
    "hairline", "hairline-strong",
    "ink", "ink-secondary", "ink-muted", "ink-inverse",
    "accent-bg", "accent-border", "accent", "accent-hover", "accent-text",
    "focus-ring",
    "danger-bg", "danger",
    "info-bg", "info",
    "warning-bg", "warning",
    "success-bg", "success",
]

# Every pairing this interface actually puts in front of somebody, and the floor
# it has to clear. Derived by reading where each token is used, not by guessing.
#
#   4.5 — WCAG AA for body-size text (SC 1.4.3)
#   3.0 — WCAG AA for user interface components and focus indicators (SC 1.4.11)
#   1.2 — not a WCAG rule: the floor below which a hairline stops being visible
#         on a good monitor in a bright kitchen
REQUIRED = [
    # Body text has to be readable on all three surfaces, not just the page.
    ("ink", "canvas", 4.5), ("ink", "raised", 4.5), ("ink", "sunken", 4.5),
    ("ink-secondary", "canvas", 4.5), ("ink-secondary", "raised", 4.5),
    ("ink-secondary", "sunken", 4.5),
    # The one that failed in August. `sunken` is the binding constraint, because
    # metadata and placeholders sit inside inputs and table headers.
    ("ink-muted", "canvas", 4.5), ("ink-muted", "raised", 4.5),
    ("ink-muted", "sunken", 4.5),

    # The label on the primary button, at rest and under the pointer.
    ("ink-inverse", "accent", 4.5), ("ink-inverse", "accent-hover", 4.5),

    # The accent as text on the pale grounds it is written on.
    ("accent-text", "canvas", 4.5), ("accent-text", "raised", 4.5),
    ("accent-text", "accent-bg", 4.5),

    # The primary button as an object: it has to be findable on the page even
    # before its label is read.
    ("accent", "canvas", 3.0), ("accent", "raised", 3.0),

    # SC 1.4.11. The focus ring is the only thing a keyboard user has.
    ("focus-ring", "canvas", 3.0), ("focus-ring", "raised", 3.0),
    ("focus-ring", "sunken", 3.0),

    # Status text on its own wash, and on the page when it is used bare.
    ("danger", "danger-bg", 4.5), ("danger", "canvas", 4.5), ("danger", "raised", 4.5),
    ("warning", "warning-bg", 4.5), ("warning", "canvas", 4.5), ("warning", "raised", 4.5),
    ("success", "success-bg", 4.5), ("success", "canvas", 4.5), ("success", "raised", 4.5),
    ("info", "info-bg", 4.5), ("info", "canvas", 4.5), ("info", "raised", 4.5),

    # Surfaces separate by tone, so the steps between them must be visible.
    ("hairline", "canvas", 1.2), ("hairline-strong", "canvas", 1.35),
    ("sunken", "canvas", 1.05),
]


# How loud a family is. Only chroma varies — every lightness in this file is
# solved against a contrast floor, so a vibrant pack and a muted one are equally
# readable and differ in saturation alone. That is the whole point: the choice a
# temple makes is about taste, and it cannot be a choice about legibility.
FAMILIES = {
    # Bright and vibrant.
    "VIBRANT": {"accent_c": 0.185, "status_c": 0.170, "neutral_c": 0.009, "wash_c": 0.055},
    # Colourful, but not loud.
    "BALANCED": {"accent_c": 0.125, "status_c": 0.150, "neutral_c": 0.007, "wash_c": 0.038},
    # Soft and muted.
    "MUTED": {"accent_c": 0.075, "status_c": 0.130, "neutral_c": 0.005, "wash_c": 0.026},
}

# Where each status colour sits on the wheel. Fixed across every pack, because
# these are not a matter of taste: red means wrong and green means done in every
# palette, and a temple that could recolour them could make its own interface
# lie. Warning is gold rather than orange so it never reads as an accent.
#
# Note how little `status_c` moves between the families compared with
# `accent_c` — 0.13 to 0.17, against 0.075 to 0.185. A muted pack is muted in
# its accent and its surfaces; its danger red is not, because a washed-out red
# on a rejected delivery is a legibility failure wearing a taste decision. The
# family sets the mood of the interface, not the volume of its alarms.
STATUS_HUES = {"danger": 27.0, "warning": 85.0, "success": 150.0, "info": 235.0}

# The lightness of the surfaces, measured off the palette this application was
# designed in so that a new pack has the same rhythm rather than a new one.
SURFACE_L = {"raised": 0.985, "sunken": 0.957, "hairline": 0.918, "hairline-strong": 0.872}


# ---------------------------------------------------------------------------
# Solving
# ---------------------------------------------------------------------------

def solve(hue, chroma, tests, prefer, lo=0.05, hi=0.99, step=0.005):
    """
    The lightest-or-darkest colour at (hue, chroma) that passes every test.

    `tests` is a list of (other_hex, floor). `prefer` is "light" or "dark": which
    end of the passing range to take. Preferring light keeps a quiet role quiet —
    ink-muted should be the lightest grey that is still readable, not the
    darkest, or "muted" stops meaning anything. Preferring dark gives a fill the
    most headroom above its floor.

    Returns None when no lightness at this chroma passes, which the caller
    answers by desaturating and asking again.
    """
    passing = []
    steps = int((hi - lo) / step) + 1
    for i in range(steps):
        L = lo + i * step
        hx = oklch_to_hex(L, chroma, hue)
        if all(ratio(hx, other) >= floor for other, floor in tests):
            passing.append(hx)
    if not passing:
        return None
    return passing[-1] if prefer == "light" else passing[0]


def solve_or_desaturate(hue, chroma, tests, prefer, label):
    """
    Solve, giving up chroma rather than contrast.

    A hue that cannot reach its floor at full saturation gets a paler version of
    itself. Which is the right trade: a slightly less colourful warning is a
    warning, and an unreadable one is not.
    """
    c = chroma
    for _ in range(12):
        got = solve(hue, c, tests, prefer)
        if got:
            return got
        c *= 0.85
    raise SystemExit(f"No lightness at any chroma satisfies {label}. Move the hue.")


def build(hue, family):
    p = FAMILIES[family]
    cn, ca, cs, cw = p["neutral_c"], p["accent_c"], p["status_c"], p["wash_c"]
    t = {}

    # --- Surfaces. Pure white page: every pack in this catalogue is a light
    # theme, and a tinted page is the fastest way to make a screenful of
    # photographs and printed job cards look wrong.
    t["canvas"] = "#FFFFFF"
    for role in ("raised", "sunken", "hairline", "hairline-strong"):
        t[role] = oklch_to_hex(SURFACE_L[role], cn, hue)

    surfaces = [(t["canvas"], 4.5), (t["raised"], 4.5), (t["sunken"], 4.5)]

    # --- Ink. Body text aims well past the floor; the two quieter greys are the
    # lightest that still clear it, so that "secondary" and "muted" stay quiet.
    # Preferring *light* against a high floor, not dark against a low one. Asking
    # for the darkest thing that clears 13.5:1 returns #000000 every time, and
    # the design system's first rule about ink is that it is never pure black: a
    # trace of the accent hue is what ties the text to the rest of the palette.
    # The lightest value that clears the floor is the one that still has hue in
    # it.
    t["ink"] = solve_or_desaturate(
        hue, cn * 1.8, [(t["canvas"], 13.5)], "light", "ink vs canvas")
    t["ink-secondary"] = solve_or_desaturate(
        hue, cn * 1.5, [(t["canvas"], 6.5)] + surfaces[1:], "light", "ink-secondary")
    t["ink-muted"] = solve_or_desaturate(
        hue, cn * 1.2, surfaces, "light", "ink-muted")

    # --- The accent. Solved before ink-inverse exists, against white, then
    # ink-inverse is solved against it. The order matters: the button's fill is
    # the fixed point of the palette and its label bends to fit, never the
    # reverse.
    #
    # The floor here is 5.0 against pure white rather than the 4.5 the label
    # actually needs, and the headroom is the point. ink-inverse is a near-white
    # carrying a whisper of the accent hue, so it is always a little darker than
    # white and always a little worse against the fill — about 5% in this
    # palette. Solve the fill at exactly 4.5 and there is no near-white left
    # that can sit on it, which is the failure this floor exists to prevent.
    #
    # Note what this means for the VIBRANT family: vibrancy has to come from
    # chroma, not from lightness. A pale bright yellow cannot be a primary fill
    # in a light interface, because nothing white enough to be "inverse" can be
    # read on it. A saturated blue at the same lightness can, and reads every
    # bit as vivid.
    t["accent"] = solve_or_desaturate(
        hue, ca,
        [("#FFFFFF", 5.0), (t["canvas"], 3.0), (t["raised"], 3.0)],
        "light", "accent")
    t["accent-hover"] = solve_or_desaturate(
        hue, ca, [("#FFFFFF", 6.0), (t["canvas"], 3.0)], "light", "accent-hover")

    # Near-white, carrying a whisper of the accent hue so a primary button does
    # not have a colder label than the page around it.
    t["ink-inverse"] = solve_or_desaturate(
        hue, cn * 1.4,
        [(t["accent"], 4.5), (t["accent-hover"], 4.5)],
        "light", "ink-inverse")

    t["accent-bg"] = oklch_to_hex(0.955, min(cw, ca * 0.42), hue)
    t["accent-border"] = oklch_to_hex(0.905, min(cw * 1.3, ca * 0.55), hue)
    t["accent-text"] = solve_or_desaturate(
        hue, ca * 0.95,
        [(t["canvas"], 4.5), (t["raised"], 4.5), (t["accent-bg"], 4.5)],
        "light", "accent-text")

    # --- The focus ring. Its own token as of 2026-08-28, because it had been
    # borrowing accent-border and accent-border has a different job: a quiet
    # hairline on a secondary button, where 3:1 is not asked of it. Sharing them
    # meant the only focus indicator in the application sat at 1.36:1 against
    # the page, which is to say it was invisible to the people who need it most.
    t["focus-ring"] = solve_or_desaturate(
        hue, ca * 0.8,
        [(t["canvas"], 3.0), (t["raised"], 3.0), (t["sunken"], 3.0)],
        "light", "focus-ring")

    # --- Status. Each wash first, then its ink solved against the wash and the
    # page together, so a badge is readable whether or not it is sitting on one.
    for role, h in STATUS_HUES.items():
        wash = oklch_to_hex(0.945, min(cw, cs * 0.4), h)
        t[f"{role}-bg"] = wash
        t[role] = solve_or_desaturate(
            h, cs,
            [(wash, 4.5), (t["canvas"], 4.5), (t["raised"], 4.5)],
            "light", role)

    return {k: t[k].upper() if t[k].startswith("#") else "#" + t[k].upper() for k in TOKENS}


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def verify(palette):
    """Every required pairing, worst first. Returns the failures."""
    rows = [(a, b, floor, ratio(palette[a], palette[b])) for a, b, floor in REQUIRED]
    rows.sort(key=lambda r: r[3] / r[2])
    return rows, [r for r in rows if r[3] < r[2]]


def entry(slug, name, family, description, palette):
    """
    The pack as it goes into frontend/lib/theme-packs.ts.

    <p>TypeScript rather than SQL, because that is where the catalogue lives. Quoted keys
    throughout — over half the token names carry a hyphen, and a mix of bare and quoted keys in
    one object reads like an accident rather than a rule.
    """
    body = "\n".join(f'      "{k}": "{palette[k]}",' for k in TOKENS)
    return (
        "  {\n"
        f'    id: "{slug}",\n'
        f'    name: "{name}",\n'
        f'    family: "{family}",\n'
        f'    description:\n      "{description}",\n'
        "    palette: {\n"
        f"{body}\n"
        "    },\n"
        "  },"
    )


def report(title, palette):
    """The palette and its pairings. Returns the number of failures."""
    rows, failures = verify(palette)

    print(f"# {title}\n")
    for token in TOKENS:
        print(f"  {token:<16} {palette[token]}")

    print(f"\n== Required pairings ({len(rows)}), tightest first ==")
    for a, b, floor, got in rows:
        mark = "FAIL" if got < floor else "ok"
        print(f"  [{mark:>4}] {a:<16} on {b:<16} {got:6.2f}  (floor {floor})")
    return len(failures)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", metavar="FILE",
                    help="Verify an existing palette (a JSON object of token -> #RRGGBB) instead "
                         "of building one. Use '-' to read it from standard input. This is how a "
                         "pack that was not produced by this script — the terracotta the "
                         "application shipped with — gets held to the same contract.")
    ap.add_argument("--slug")
    ap.add_argument("--name")
    ap.add_argument("--family", choices=list(FAMILIES))
    ap.add_argument("--description")
    ap.add_argument("--hue", type=float,
                    help="The accent hue in OKLCH degrees. 25 terracotta, 145 green, 250 blue.")
    ap.add_argument("--json", action="store_true", help="Print the palette as JSON and nothing else.")
    args = ap.parse_args()

    if args.check:
        raw = sys.stdin.read() if args.check == "-" else open(args.check).read()
        existing = json.loads(raw)
        missing = [t for t in TOKENS if t not in existing]
        if missing:
            sys.exit(f"That palette is missing {len(missing)} token(s): {', '.join(missing)}")
        sys.exit(1 if report(args.check, existing) else 0)

    for required in ("slug", "name", "family", "description", "hue"):
        if getattr(args, required) is None:
            ap.error(f"--{required} is required when building a pack")

    palette = build(args.hue, args.family)

    if args.json:
        print(json.dumps(palette, indent=2))
        return

    failures = report(f"{args.name} — {args.family.lower()}, hue {args.hue}", palette)
    if failures:
        print(f"\n{failures} pairing(s) failed. This pack is not shippable.")
        sys.exit(1)

    print("\n== Paste into frontend/lib/theme-packs.ts ==\n")
    print(entry(args.slug, args.name, args.family, args.description, palette))


if __name__ == "__main__":
    main()
