#!/usr/bin/env python3
"""
Imports a `theme-packs.json` handoff into the application.

    python3 tools/theme/import_theme_packs.py ~/Downloads/theme-packs.json

Validates first and writes nothing unless everything passes: all 28 roles present and no others,
every value a six-digit hex, identifiers unique and of the shape the database will accept, and all
39 contrast pairings clearing their floors in every pack. Then it rewrites two files —
`frontend/lib/theme-packs.ts` (the catalogue) and the `:root` block of `frontend/app/globals.css`
(the default palette compiled into the stylesheet, so a signed-out screen has colours before any
JavaScript runs).

This exists because the alternative is transcribing several hundred hex values by hand, which is
how a typo gets in — and a mistyped colour does not fail loudly. It leaves one surface wearing the
previous theme and looks like a rendering bug.

The same checks run again in `frontend/__tests__/theme-contract.test.ts` on every commit. This one
stops a bad pack being written; that one stops it being shipped.
"""

import argparse
import json
import os
import pathlib
import re
import sys

SKILL = os.environ.get("TASTEMAKER_SCRIPTS", os.path.expanduser("~/.claude/skills/tastemaker/scripts"))
if not os.path.isdir(SKILL):
    sys.exit(f"tastemaker's scripts are not at {SKILL}. See tools/theme/build_theme_pack.py.")
sys.path.insert(0, SKILL)
from check_contrast import ratio  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]

TOKENS = [
    "canvas", "raised", "sunken", "hairline", "hairline-strong",
    "ink", "ink-secondary", "ink-muted", "ink-inverse",
    "accent-bg", "accent-border", "accent", "accent-hover", "accent-text", "focus-ring",
    "danger-bg", "danger", "info-bg", "info", "warning-bg", "warning", "success-bg", "success",
    "meter-low", "meter-mid", "meter-high", "meter-pledged", "meter-neutral",
]

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
    ("meter-low", "sunken", 3.0), ("meter-mid", "sunken", 3.0), ("meter-high", "sunken", 3.0),
    ("meter-pledged", "sunken", 3.0), ("meter-neutral", "sunken", 3.0),
    ("hairline", "canvas", 1.2), ("hairline-strong", "canvas", 1.35), ("sunken", "canvas", 1.05),
]

# Raw CSS a pack may carry, applied verbatim. Optional: absent means flat.
SURFACES = ["shadow-card", "shadow-raised", "shadow-overlay", "accent-gradient", "surface-blur"]

GROUPS = {"vibrant": "VIBRANT", "balanced": "BALANCED", "muted": "MUTED"}
ID_SHAPE = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
HEX = re.compile(r"#[0-9A-Fa-f]{6}")


def validate(packs):
    problems = []
    ids = [p.get("id") for p in packs]
    for dup in {i for i in ids if ids.count(i) > 1}:
        problems.append(f"duplicate id: {dup}")

    for p in packs:
        who = p.get("name") or p.get("id") or "(unnamed)"
        if not ID_SHAPE.fullmatch(p.get("id", "")):
            problems.append(f"{who}: id {p.get('id')!r} is not lower-case-and-hyphens")
        if p.get("group") not in GROUPS:
            problems.append(f"{who}: group {p.get('group')!r} is not one of {list(GROUPS)}")
        if not (p.get("description") or "").strip():
            problems.append(f"{who}: no description")

        palette = p.get("palette", {})
        for token in TOKENS:
            if token not in palette:
                problems.append(f"{who}: missing role {token}")
            elif not HEX.fullmatch(str(palette[token])):
                problems.append(f"{who}: {token} is {palette[token]!r}, not a six-digit hex")
        for extra in set(palette) - set(TOKENS):
            problems.append(f"{who}: unknown role {extra}")

        surfaces = p.get("surfaces", {})
        for extra in set(surfaces) - set(SURFACES):
            problems.append(f"{who}: unknown surface token {extra}")

        # A gradient is the one new token that can make something unreadable. Its stops sit under
        # the button's own label, so each of them has to carry that label exactly as the flat
        # `accent` does — a gradient whose dark end swallows its text is the failure this checks.
        gradient = surfaces.get("accent-gradient", "none")
        if gradient and gradient != "none" and "ink-inverse" in palette:
            stops = HEX.findall(gradient)
            if not stops:
                problems.append(f"{who}: accent-gradient names no colours this can check: {gradient!r}")
            for stop in stops:
                got = ratio(stop, palette["ink-inverse"])
                if got < 4.5:
                    problems.append(
                        f"{who}: the gradient stop {stop} carries ink-inverse at {got:.2f}, needs 4.5")

        if all(t in palette and HEX.fullmatch(str(palette[t])) for t in TOKENS):
            for a, b, floor in REQUIRED:
                got = ratio(palette[a], palette[b])
                if got < floor:
                    problems.append(f"{who}: {a} on {b} is {got:.2f}, needs {floor}")
    return problems


def entry(p):
    palette = p["palette"]
    body = "\n".join(f'      "{t}": "{palette[t].upper()}",' for t in TOKENS)
    description = p["description"].strip().replace('"', '\\"').replace("'", "’")
    out = (f'  {{\n    id: "{p["id"]}",\n    name: "{p["name"]}",\n'
           f'    family: "{GROUPS[p["group"]]}",\n'
           f'    description:\n      "{description}",\n'
           f'    palette: {{\n{body}\n    }},\n')
    surfaces = {k: v for k, v in (p.get("surfaces") or {}).items() if k in SURFACES}
    if surfaces:
        rows = "\n".join(f'      "{k}": "{surfaces[k]}",' for k in SURFACES if k in surfaces)
        out += f'    surfaces: {{\n{rows}\n    }},\n'
    return out + "  },"


def channels(hex_value):
    n = int(hex_value.lstrip("#"), 16)
    return f"{(n >> 16) & 255} {(n >> 8) & 255} {n & 255}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", help="the theme-packs.json handoff")
    ap.add_argument("--default", default="terracotta",
                    help="the id of the pack every temple starts on and unknown choices fall back to")
    ap.add_argument("--check", action="store_true", help="validate and report, writing nothing")
    args = ap.parse_args()

    data = json.loads(pathlib.Path(args.file).read_text())
    packs = data["packs"] if isinstance(data, dict) else data

    problems = validate(packs)
    if not any(p["id"] == args.default for p in packs):
        problems.append(f"the default {args.default!r} is not one of the packs")
    if problems:
        print(f"{len(problems)} problem(s); nothing written:\n")
        for line in problems:
            print("  " + line)
        sys.exit(1)

    print(f"{len(packs)} packs, {len(REQUIRED)} pairings each — all pass.")
    if args.check:
        return

    # The catalogue. Ordered loud to quiet, which is the order the picker groups them in.
    order = {"vibrant": 0, "balanced": 1, "muted": 2}
    # Enumerate first: sorting by `packs.index(p)` reads a list that `sort` is already rearranging.
    packs = [p for _, p in sorted(enumerate(packs), key=lambda t: (order[t[1]["group"]], t[0]))]

    catalogue = ROOT / "frontend" / "lib" / "theme-packs.ts"
    s = catalogue.read_text()
    start = s.index("export const THEME_PACKS: ThemePack[] = [")
    end = s.index("\n];", start) + len("\n];")
    s = (s[:start] + "export const THEME_PACKS: ThemePack[] = [\n"
         + "\n".join(entry(p) for p in packs) + "\n];" + s[end:])
    s = re.sub(r'export const DEFAULT_THEME_ID = "[^"]*";',
               f'export const DEFAULT_THEME_ID = "{args.default}";', s)
    catalogue.write_text(s)
    print(f"wrote {catalogue.relative_to(ROOT)}")

    # The compiled default, so a signed-out screen and the first paint have colours.
    default = next(p for p in packs if p["id"] == args.default)["palette"]
    css = ROOT / "frontend" / "app" / "globals.css"
    t = css.read_text()
    block = "\n".join(f"  --kms-{k}: {channels(default[k])}; /* {default[k].upper()} */" for k in TOKENS)
    t = re.sub(r":root \{\n.*?\n\}", ":root {\n" + block + "\n}", t, count=1, flags=re.S)
    css.write_text(t)
    print(f"wrote the :root block of {css.relative_to(ROOT)}")


main()
