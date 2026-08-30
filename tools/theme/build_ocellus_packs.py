#!/usr/bin/env python3
"""
Turns the Ocellus handoff into theme packs that clear our contrast contract.

The palette is taken as given wherever it passes. Two tokens do not pass and are
solved along their own hue, so the colour stays the designer's and only its
lightness moves. Three tokens the handoff does not carry are derived.
"""
import math
import os
import sys

sys.path.insert(0, os.path.expanduser("~/.claude/skills/tastemaker/scripts"))
sys.path.insert(0, "tools/theme")
from check_contrast import ratio                                    # noqa: E402
from generate_palette import oklch_to_hex, _srgb_to_linear, _linear_rgb_to_oklab  # noqa: E402


def oklch(hx):
    hx = hx.lstrip("#")
    r, g, b = [int(hx[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    R, G, B = [_srgb_to_linear(c) for c in (r, g, b)]
    L, a, bb = _linear_rgb_to_oklab(R, G, B)
    return L, math.hypot(a, bb), math.degrees(math.atan2(bb, a)) % 360


def solve(hue, chroma, tests, prefer, lo=0.05, hi=0.99, step=0.004):
    passing = []
    n = int((hi - lo) / step) + 1
    for i in range(n):
        L = lo + i * step
        hx = oklch_to_hex(L, chroma, hue)
        if all(ratio(hx, other) >= floor for other, floor in tests):
            passing.append(hx)
    if not passing:
        return None
    return "#" + (passing[-1] if prefer == "light" else passing[0]).lstrip("#").upper()


def solve_or_desaturate(hue, chroma, tests, prefer, label):
    c = chroma
    for _ in range(14):
        got = solve(hue, c, tests, prefer)
        if got:
            return got
        c *= 0.85
    sys.exit(f"no lightness satisfies {label}")


# --------------------------------------------------------------------------
# The handoff, transcribed. Base first, then each variant's overrides exactly
# as the commented blocks in colors.css give them.
# --------------------------------------------------------------------------

BASE = {
    "canvas": "#FFFFFF", "raised": "#F7F7FB", "sunken": "#ECECF3",
    "hairline": "#DBDBE6", "hairline-strong": "#C9C9DA",
    "ink": "#1B1C33", "ink-secondary": "#545675", "ink-muted": "#8E90A8",
    "ink-inverse": "#F8F8FC",
    "accent-bg": "#E8E7F4", "accent-border": "#D2D1EA", "accent": "#3B3A8F",
    "accent-hover": "#2C2B72", "accent-text": "#2C2B72",
    "danger-bg": "#F5E5E3", "danger": "#9B2C1F",
    "warning-bg": "#F2E7D4", "warning": "#7F5820",
    "success-bg": "#E4EEE9", "success": "#2F6B54",
    "meter-low": "#B4462F", "meter-mid": "#C08A2E", "meter-high": "#2F6B54",
    "meter-pledged": "#C9A25A", "meter-neutral": "#A7A9BC",
}

VARIANTS = [
    ("ocellus", "Ocellus",
     "Peacock-eye indigo on cool white. The eye of the feather rather than the barbs.", {}),
    ("ocellus-green-barbule", "Ocellus / green barbule",
     "Indigo, with the feather’s green in the neutrals and in everything that has gone right.",
     {"raised": "#F6F8F7", "sunken": "#E9EEEC", "hairline": "#D8E2DE",
      "hairline-strong": "#C4D2CC", "success": "#1E7A5F", "success-bg": "#E1EFE9",
      "meter-high": "#1E7A5F"}),
    ("ocellus-copper", "Ocellus / copper",
     "Indigo over warm neutrals, with copper carrying every warning and every pledge.",
     {"raised": "#F9F7F5", "sunken": "#EFEBE7", "hairline": "#E1DAD3",
      "hairline-strong": "#D0C6BD", "warning": "#8A5525", "warning-bg": "#F4E7DA",
      "meter-mid": "#B4703A", "meter-pledged": "#D2A06B"}),
    ("ocellus-pewter", "Ocellus / pewter",
     "Indigo over cool grey, with graphite ink. The quiet one.",
     {"raised": "#F6F7F8", "sunken": "#EAECEF", "hairline": "#D7DBE0",
      "hairline-strong": "#C3C8D0", "ink": "#23262E", "ink-secondary": "#5A6069",
      "ink-muted": "#8B9199", "meter-neutral": "#7E858F"}),
    ("ocellus-full-plume", "Ocellus / full plume",
     "The whole feather: indigo, pewter neutrals, peacock green for done, copper for careful.",
     {"raised": "#F6F7F8", "sunken": "#EAECEF", "hairline": "#D7DBE0",
      "hairline-strong": "#C3C8D0", "success": "#1E7A5F", "success-bg": "#E1EFE9",
      "meter-high": "#1E7A5F", "warning": "#8A5525", "warning-bg": "#F4E7DA",
      "meter-mid": "#B4703A", "meter-pledged": "#D2A06B", "meter-neutral": "#7E858F"}),
]

TOKENS = [
    "canvas", "raised", "sunken", "hairline", "hairline-strong",
    "ink", "ink-secondary", "ink-muted", "ink-inverse",
    "accent-bg", "accent-border", "accent", "accent-hover", "accent-text", "focus-ring",
    "danger-bg", "danger", "info-bg", "info", "warning-bg", "warning", "success-bg", "success",
    "meter-low", "meter-mid", "meter-high", "meter-pledged", "meter-neutral",
]


def complete(base):
    """Fills the three tokens the handoff has no answer for, and fixes the two that fail."""
    p = dict(base)
    notes = []

    # --- ink-muted. #8E90A8 measures 3.13 on canvas and 2.66 on sunken, against 4.5. The same
    # failure this palette's predecessor had, on the same token. Darkened along its own hue so it
    # stays the designer's grey, and no further than it has to go.
    L, C, H = oklch(p["ink-muted"])
    fixed = solve_or_desaturate(
        H, C, [(p["canvas"], 4.5), (p["raised"], 4.5), (p["sunken"], 4.5)], "light", "ink-muted")
    if fixed.lower() != p["ink-muted"].lower():
        notes.append(f"ink-muted {p['ink-muted']} -> {fixed} (was {ratio(p['ink-muted'], p['sunken']):.2f} on sunken)")
        p["ink-muted"] = fixed

    # --- focus-ring. The handoff points --shadow-focus at --accent-border, which measures 1.49 on
    # the page against the 3:1 WCAG 2.2 asks of a focus indicator. Its own token here, at the accent
    # hue, as light as it can be while clearing the floor on all three surfaces.
    L, C, H = oklch(p["accent"])
    p["focus-ring"] = solve_or_desaturate(
        H, C * 0.85, [(p["canvas"], 3.0), (p["raised"], 3.0), (p["sunken"], 3.0)],
        "light", "focus-ring")
    notes.append(f"focus-ring {p['focus-ring']} derived (accent-border was {ratio(p['accent-border'], p['canvas']):.2f} on canvas)")

    # --- info. Not in the handoff at all; this system has carried it since v1.2 for the Vaishnava
    # calendar. Teal rather than blue, deliberately: the accent here is indigo, and their own rule
    # is that a status colour must never be mistakable for something to click.
    p["info-bg"] = "#E2EFF3"
    p["info"] = solve_or_desaturate(
        230.0, 0.085, [(p["info-bg"], 4.5), (p["canvas"], 4.5), (p["raised"], 4.5)], "light", "info")
    notes.append(f"info {p['info']} on {p['info-bg']} derived (away from the indigo accent)")

    # --- Anything still short of its floor is moved along its own hue and no further. The colour
    # stays the designer's; only its lightness changes, and only until it clears. Meters are the
    # usual casualties: a pale fill in a pale track is a bar whose end you cannot find.
    for token, against, floor in REPAIRABLE:
        if ratio(p[token], p[against]) < floor:
            was = p[token]
            L, C, H = oklch(was)
            p[token] = solve_or_desaturate(H, C, [(p[against], floor)], "light", token)
            notes.append(f"{token} {was} -> {p[token]} (was {ratio(was, p[against]):.2f} on {against})")
    return p, notes


# Tokens that may be nudged when they fall short, and what they fall short against. Surfaces are
# not on this list: moving `sunken` to rescue a meter would repaint every input on every screen to
# fix a progress bar.
REPAIRABLE = [
    ("meter-low", "sunken", 3.0), ("meter-mid", "sunken", 3.0), ("meter-high", "sunken", 3.0),
    ("meter-pledged", "sunken", 3.0), ("meter-neutral", "sunken", 3.0),
    ("success", "success-bg", 4.5), ("warning", "warning-bg", 4.5), ("danger", "danger-bg", 4.5),
]


# --------------------------------------------------------------------------
# The contract
# --------------------------------------------------------------------------

REQUIRED = [
    ("ink", "canvas", 4.5), ("ink", "raised", 4.5), ("ink", "sunken", 4.5),
    ("ink-secondary", "canvas", 4.5), ("ink-secondary", "raised", 4.5), ("ink-secondary", "sunken", 4.5),
    ("ink-muted", "canvas", 4.5), ("ink-muted", "raised", 4.5), ("ink-muted", "sunken", 4.5),
    ("ink-inverse", "accent", 4.5), ("ink-inverse", "accent-hover", 4.5),
    ("accent-text", "canvas", 4.5), ("accent-text", "raised", 4.5), ("accent-text", "accent-bg", 4.5),
    ("accent", "canvas", 3.0), ("accent", "raised", 3.0),
    ("focus-ring", "canvas", 3.0), ("focus-ring", "raised", 3.0), ("focus-ring", "sunken", 3.0),
    ("danger", "danger-bg", 4.5), ("danger", "canvas", 4.5), ("danger", "raised", 4.5),
    ("warning", "warning-bg", 4.5), ("warning", "canvas", 4.5), ("warning", "raised", 4.5),
    ("success", "success-bg", 4.5), ("success", "canvas", 4.5), ("success", "raised", 4.5),
    ("info", "info-bg", 4.5), ("info", "canvas", 4.5), ("info", "raised", 4.5),
    ("hairline", "canvas", 1.2), ("hairline-strong", "canvas", 1.35), ("sunken", "canvas", 1.05),
    # A meter is a fill in a sunken track. It never carries text, so 3:1 against the track it sits
    # in is the floor — enough to see where the bar ends.
    ("meter-low", "sunken", 3.0), ("meter-mid", "sunken", 3.0), ("meter-high", "sunken", 3.0),
    ("meter-pledged", "sunken", 3.0), ("meter-neutral", "sunken", 3.0),
]


def entry(slug, name, description, p):
    body = "\n".join(f'      "{k}": "{p[k].upper()}",' for k in TOKENS)
    return (f'  {{\n    id: "{slug}",\n    name: "{name}",\n    family: "VIBRANT",\n'
            f'    description:\n      "{description}",\n    palette: {{\n{body}\n    }},\n  }},')


def main():
    out = []
    for slug, name, desc, overrides in VARIANTS:
        p, notes = complete({**BASE, **overrides})
        rows = sorted(((a, b, f, ratio(p[a], p[b])) for a, b, f in REQUIRED), key=lambda r: r[3] / r[2])
        fails = [r for r in rows if r[3] < r[2]]

        print(f"\n{'=' * 72}\n{name}  ({slug})\n{'=' * 72}")
        for n in notes:
            print(f"  adjusted: {n}")
        print(f"  {len(rows) - len(fails)}/{len(rows)} pairings pass. Tightest three:")
        for a, b, f, got in rows[:3]:
            print(f"    {a:<16} on {b:<16} {got:6.2f}  (floor {f})")
        for a, b, f, got in fails:
            print(f"    FAIL {a} on {b}: {got:.2f} < {f}")
        out.append(entry(slug, name, desc, p))

    open("/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/"
         "95d5f6af-ccf0-4b0d-85c2-1195899516eb/scratchpad/ocellus-entries.ts",
         "w").write("\n".join(out) + "\n")
    print("\nEntries written.")


main()
