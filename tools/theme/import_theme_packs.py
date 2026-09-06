#!/usr/bin/env python3
"""
Imports a `theme-packs.json` handoff into the application.

    python3 tools/theme/import_theme_packs.py ~/Downloads/theme-packs-v2.json

Reads the v2 shape described by `THEME-TOKENS.md`: one flat `palette` of 49 keys per pack — 28
colours, 20 surface treatments that are complete CSS values, and `finish`. Validates first and
writes nothing if the *structure* is wrong: a missing key, an unknown key, a value that is not a
six-digit hex where a hex is required, an identifier the database would refuse, a finish that
disagrees with its group.

Then it rewrites three files:

  * `frontend/lib/theme-packs.ts` — the catalogue.
  * `frontend/app/globals.css` — the default pack compiled into the stylesheet, so a signed-out
    screen has colours before any JavaScript runs. Written twice, under both naming schemes: see
    the marker comments in that file.
  * `frontend/lib/theme-contrast-waivers.json` — see below.

This exists because the alternative is transcribing several hundred values by hand, which is how a
typo gets in — and a mistyped colour does not fail loudly. It leaves one surface wearing the
previous theme and looks like a rendering bug.

**On contrast.** This project checks forty-four pairings; `THEME-TOKENS.md` §7 audits twenty-two of
them. Where a pack falls short on a pairing §7 never audited, refusing the whole import would mean
holding fifteen packs hostage to a floor their designer was never given. So a shortfall is recorded
rather than fatal: the measured ratio is written to `theme-contrast-waivers.json`, and
`theme-contract.test.ts` then fails if that pairing ever gets *worse*, or if a pairing fails that is
not on the list. The check is pinned, not deleted. Run with `--strict` to refuse the import instead.

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

# The colours. Every one a six-digit hex, because they are converted to channels for Tailwind's
# opacity modifier and a hex is the only form that conversion accepts.
COLOURS = [
    "canvas", "raised", "sunken", "hairline", "hairline-strong",
    "ink", "ink-secondary", "ink-muted", "ink-inverse",
    "accent-bg", "accent-border", "accent", "accent-hover", "accent-text", "focus-ring",
    "danger-bg", "danger", "info-bg", "info", "warning-bg", "warning", "success-bg", "success",
    "meter-low", "meter-mid", "meter-high", "meter-pledged", "meter-neutral",
]

# The surface treatment. Complete CSS values, applied verbatim and never parsed — a border token
# carries its own width and style, a background token may be three stacked gradients. Order matches
# SURFACE_TOKENS in `frontend/lib/theme.ts`, which is the contract these are read through.
SURFACES = [
    "canvas-bg",
    "surface-card-bg", "surface-card-border", "surface-card-backdrop", "surface-card-sheen",
    "table-header-bg", "input-bg",
    "btn-primary-bg", "btn-primary-border", "btn-primary-shadow",
    "btn-secondary-bg", "btn-secondary-border", "btn-secondary-shadow",
    "radius-card", "radius-control",
    "shadow-card", "shadow-raised", "shadow-overlay",
    "accent-gradient", "surface-blur",
]

GROUPS = {"vibrant": "VIBRANT", "balanced": "BALANCED", "muted": "MUTED"}

# §6. The finish is a property of the group, not a free choice per pack: the three groups exist to
# be told apart, and a frosted pack filed under "bright and vibrant" would undo that.
FINISH_FOR_GROUP = {"vibrant": "glossy", "balanced": "frosted", "muted": "flat"}

ID_SHAPE = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
HEX = re.compile(r"#[0-9A-Fa-f]{6}")

# Every pairing this interface actually puts in front of somebody, and the floor it has to clear.
# 4.5 is WCAG AA for body text (SC 1.4.3); 3.0 is AA for interface components and focus indicators
# (SC 1.4.11); 1.2 and 1.35 are not WCAG rules but the floor below which a hairline stops being
# visible on a cheap monitor in a bright kitchen. Kept identical to the list in
# `frontend/__tests__/theme-contract.test.ts`.
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

# The pairings a gradient makes that a flat colour does not. §4 puts `ink-inverse` on the primary
# button and §6 makes that button's fill a gradient, but §7 audits only `ink-inverse` on the flat
# `accent` — and a gradient's lightest stop is lighter than `accent` by construction. So each stop
# is checked as if it were the whole fill, because for the text sitting on top of it, it is.
GRADIENT_FILLS = [("btn-primary-bg", "ink-inverse", 4.5), ("accent-gradient", "ink-inverse", 4.5)]


def structural_problems(packs):
    """Everything that makes a pack unusable rather than merely imperfect. Any of these and we stop."""
    problems = []
    ids = [p.get("id") for p in packs]
    for dup in sorted({i for i in ids if ids.count(i) > 1}):
        problems.append(f"duplicate id: {dup}")

    for p in packs:
        who = p.get("name") or p.get("id") or "(unnamed)"
        if not ID_SHAPE.fullmatch(p.get("id", "")):
            problems.append(f"{who}: id {p.get('id')!r} is not lower-case-and-hyphens")
        if p.get("group") not in GROUPS:
            problems.append(f"{who}: group {p.get('group')!r} is not one of {list(GROUPS)}")
        if not (p.get("description") or "").strip():
            problems.append(f"{who}: no description")

        palette = dict(p.get("palette") or {})
        # A handoff may put the surface tokens in a `surfaces` object of their own or mix them into
        # `palette`, and both are reasonable readings of the brief. v2 does the latter. Rather than
        # insist on one, merge — the two sets share no key, so the reading is unambiguous.
        palette.update(p.get("surfaces") or {})

        for token in COLOURS:
            if token not in palette:
                problems.append(f"{who}: missing colour {token}")
            elif not HEX.fullmatch(str(palette[token])):
                problems.append(f"{who}: {token} is {palette[token]!r}, not a six-digit hex")
        for token in SURFACES:
            if token not in palette:
                problems.append(f"{who}: missing surface token {token}")
            elif not str(palette[token]).strip():
                problems.append(f"{who}: {token} is empty")

        expected_finish = FINISH_FOR_GROUP.get(p.get("group"))
        if palette.get("finish") != expected_finish:
            problems.append(
                f"{who}: finish is {palette.get('finish')!r}; a {p.get('group')!r} pack is "
                f"{expected_finish!r}")

        for extra in sorted(set(palette) - set(COLOURS) - set(SURFACES) - {"finish"}):
            problems.append(f"{who}: unknown key {extra}")

    return problems


def shortfalls(pack):
    """
    Every pairing this pack fails, with what it actually measures.

    <p>Returns `{"a/b": ratio}`. The key is the pairing rather than the pack, because the caller
    files these under the pack's id and a flat map is what the test reads.
    """
    palette = dict(pack.get("palette") or {})
    palette.update(pack.get("surfaces") or {})
    out = {}

    for a, b, floor in REQUIRED:
        got = ratio(palette[a], palette[b])
        if got < floor:
            out[f"{a}/{b}"] = round(got, 2)

    # A gradient fill is checked stop by stop. `none` and a flat hex both come back as a single
    # colour or none at all, so this covers the flat packs without a branch.
    for fill, text, floor in GRADIENT_FILLS:
        value = str(palette.get(fill, "none"))
        if value == "none":
            continue
        for stop in dict.fromkeys(HEX.findall(value)):
            got = ratio(stop, palette[text])
            if got < floor:
                out[f"{fill}:{stop.upper()}/{text}"] = round(got, 2)

    return out


def channels(hex_value):
    n = int(hex_value.lstrip("#"), 16)
    return f"{(n >> 16) & 255} {(n >> 8) & 255} {n & 255}"


def entry(p):
    """One pack as it appears in the catalogue."""
    palette = dict(p.get("palette") or {})
    palette.update(p.get("surfaces") or {})
    colours = "\n".join(f'      "{t}": "{palette[t].upper()}",' for t in COLOURS)
    surfaces = "\n".join(f'      "{t}": "{palette[t]}",' for t in SURFACES)
    description = p["description"].strip().replace('"', '\\"').replace("'", "’")
    return (f'  {{\n    id: "{p["id"]}",\n    name: "{p["name"]}",\n'
            f'    family: "{GROUPS[p["group"]]}",\n'
            f'    finish: "{palette["finish"]}",\n'
            f'    description:\n      "{description}",\n'
            f'    palette: {{\n{colours}\n    }},\n'
            f'    surfaces: {{\n{surfaces}\n    }},\n'
            f'  }},')


def replace_block(css, marker, body):
    """
    Rewrites the declarations between a pair of marker comments, leaving the prose around them.

    <p>Markers rather than "the third `:root` in the file", because the prose between these blocks
    is worth keeping and counting braces through it is how a generator eats a comment.
    """
    start = f"/* >>> generated: {marker} */"
    end = f"/* <<< generated: {marker} */"
    i, j = css.index(start), css.index(end)
    return css[:i] + start + "\n" + body + "\n  " + css[j:]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", help="the theme-packs.json handoff")
    ap.add_argument("--default", default="terracotta",
                    help="the id of the pack every temple starts on and unknown choices fall back to")
    ap.add_argument("--check", action="store_true", help="validate and report, writing nothing")
    ap.add_argument("--strict", action="store_true",
                    help="treat a contrast shortfall as fatal rather than recording it")
    args = ap.parse_args()

    data = json.loads(pathlib.Path(args.file).read_text())
    packs = data["packs"] if isinstance(data, dict) else data

    problems = structural_problems(packs)
    if not any(p["id"] == args.default for p in packs):
        problems.append(f"the default {args.default!r} is not one of the packs")
    if problems:
        print(f"{len(problems)} structural problem(s); nothing written:\n")
        for line in problems:
            print("  " + line)
        sys.exit(1)

    waivers = {p["id"]: s for p in packs if (s := shortfalls(p))}
    total = sum(len(s) for s in waivers.values())
    checks = len(REQUIRED) * len(packs)
    if total:
        print(f"{total} contrast shortfall(s) across {len(waivers)} of {len(packs)} packs:\n")
        for pack_id, found in waivers.items():
            for pairing, got in sorted(found.items()):
                print(f"  {pack_id}: {pairing} is {got}")
        print()
        if args.strict:
            print("--strict; nothing written.")
            sys.exit(1)
    print(f"{len(packs)} packs, {len(REQUIRED)} pairings each, {checks - total} of {checks} clear.")
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

    # The default pack, compiled into the stylesheet, under both naming schemes. `--kms-*` holds
    # channels for Tailwind's opacity modifier; the bare names hold whole values for the §4 recipe.
    fallback = dict(next(p for p in packs if p["id"] == args.default)["palette"])
    stylesheet = ROOT / "frontend" / "app" / "globals.css"
    css = stylesheet.read_text()
    css = replace_block(css, "default palette, as channels", "\n".join(
        f"  --kms-{t}: {channels(fallback[t])}; /* {fallback[t].upper()} */" for t in COLOURS))
    css = replace_block(css, "default surfaces", "\n".join(
        f"  --kms-{t}: {fallback[t]};" for t in SURFACES))
    css = replace_block(css, "default palette, under the contract's own names", "\n".join(
        [f"  --{t}: {fallback[t].upper()};" for t in COLOURS] + [""]
        + [f"  --{t}: {fallback[t]};" for t in SURFACES]))
    stylesheet.write_text(css)
    print(f"wrote {stylesheet.relative_to(ROOT)}")

    # And the shortfalls, pinned so they cannot quietly get worse. See the module docstring.
    record = ROOT / "frontend" / "lib" / "theme-contrast-waivers.json"
    record.write_text(json.dumps({
        "_": ("Measured contrast shortfalls, written by tools/theme/import_theme_packs.py and read "
              "by __tests__/theme-contract.test.ts. A pairing here fails its floor but is allowed "
              "to ship at no worse than the value recorded. Do not hand-edit: re-import instead. "
              "Every entry is a pairing THEME-TOKENS.md §7 does not audit."),
        "packs": waivers,
    }, indent=2, sort_keys=True) + "\n")
    print(f"wrote {record.relative_to(ROOT)} ({total} pinned)")


if __name__ == "__main__":
    main()
