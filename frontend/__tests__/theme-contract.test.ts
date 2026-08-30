import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { hexToChannels, THEME_TOKENS, type ThemePalette, type ThemeToken } from "@/lib/theme";
import {
  DEFAULT_THEME_ID,
  DEFAULT_THEME_PACK,
  THEME_PACKS,
  themePackById,
} from "@/lib/theme-packs";

/**
 * Every theme pack, held to the contract, on every commit.
 *
 * <p>This is the check that replaced a database constraint, and it is a great deal stronger than
 * the one it replaced. A `CHECK` could assert that a pack had all twenty-three keys. It could not
 * assert the thing that actually matters — that a person can *read* the result — because that is a
 * property of pairs of colours, and a constraint can only see one row's columns.
 *
 * <p>It is worth being blunt about why this file exists. Both contrast failures in this project's
 * history were introduced by somebody choosing a colour they liked and not thinking to check a
 * pairing: `ink-muted` on 2026-08-20, which failed on all three surfaces, and the focus ring found
 * on 2026-08-28, which had been sitting at 1.36:1 against the page since the palette was written.
 * Fifteen packs is fifteen times the opportunity to do it again, and no amount of care survives
 * that. So the care is here, where it runs whether anybody remembers it or not.
 *
 * <p>The floors are the same ones `tools/theme/build_theme_pack.py` solves against. The tool stops
 * a bad pack being *written*; this stops one being *shipped*, including one somebody edited by hand
 * afterwards.
 */

// ---------------------------------------------------------------------------
// WCAG 2.1 relative luminance and contrast ratio.
//
// Reimplemented here rather than imported from the build tool, which is Python and does not run in
// vitest. Twelve lines, and the arithmetic has not changed since 2008 — but two independent copies
// of a formula is exactly the kind of thing that drifts, so the tool's own output is checked
// against this one below.
// ---------------------------------------------------------------------------

function luminance(hex: string): number {
  const channels = hexToChannels(hex);
  if (!channels) {
    throw new Error(`Not a colour: ${hex}`);
  }
  const [r, g, b] = channels.split(" ").map((n) => {
    const c = Number(n) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Every pairing this interface actually puts in front of somebody, and the floor it has to clear.
 *
 * <p>Derived by reading where each token is used, not by guessing. 4.5 is WCAG AA for body-size
 * text (SC 1.4.3); 3.0 is AA for user interface components and focus indicators (SC 1.4.11); 1.2
 * and 1.35 are not WCAG rules at all but the floor below which a hairline stops being visible on a
 * cheap monitor in a bright kitchen.
 */
const REQUIRED: [ThemeToken, ThemeToken, number][] = [
  // Body text has to be readable on all three surfaces, not just on the page.
  ["ink", "canvas", 4.5],
  ["ink", "raised", 4.5],
  ["ink", "sunken", 4.5],
  ["ink-secondary", "canvas", 4.5],
  ["ink-secondary", "raised", 4.5],
  ["ink-secondary", "sunken", 4.5],
  // The one that failed in August. `sunken` binds, because metadata and placeholders sit inside
  // inputs and table headers rather than on the page.
  ["ink-muted", "canvas", 4.5],
  ["ink-muted", "raised", 4.5],
  ["ink-muted", "sunken", 4.5],

  // The label on the primary button, at rest and under the pointer.
  ["ink-inverse", "accent", 4.5],
  ["ink-inverse", "accent-hover", 4.5],

  // The accent as text, on each of the pale grounds it is written on.
  ["accent-text", "canvas", 4.5],
  ["accent-text", "raised", 4.5],
  ["accent-text", "accent-bg", 4.5],

  // The primary button as an object: findable on the page before its label is read.
  ["accent", "canvas", 3.0],
  ["accent", "raised", 3.0],

  // SC 1.4.11. The focus ring is the only thing a keyboard user has.
  ["focus-ring", "canvas", 3.0],
  ["focus-ring", "raised", 3.0],
  ["focus-ring", "sunken", 3.0],

  // Status text on its own wash, and on the page where it is used bare.
  ["danger", "danger-bg", 4.5],
  ["danger", "canvas", 4.5],
  ["danger", "raised", 4.5],
  ["warning", "warning-bg", 4.5],
  ["warning", "canvas", 4.5],
  ["warning", "raised", 4.5],
  ["success", "success-bg", 4.5],
  ["success", "canvas", 4.5],
  ["success", "raised", 4.5],
  ["info", "info-bg", 4.5],
  ["info", "canvas", 4.5],
  ["info", "raised", 4.5],

  // A meter is a fill in a sunken track and carries no text, so the whole requirement is that
  // somebody can see where the bar ends.
  ["meter-low", "sunken", 3.0],
  ["meter-mid", "sunken", 3.0],
  ["meter-high", "sunken", 3.0],
  ["meter-pledged", "sunken", 3.0],
  ["meter-neutral", "sunken", 3.0],

  // Surfaces separate by tone, so the steps between them have to be visible at all.
  ["hairline", "canvas", 1.2],
  ["hairline-strong", "canvas", 1.35],
  ["sunken", "canvas", 1.05],
];

describe("the catalogue", () => {
  it("has at least one pack, and the default is one of them", () => {
    expect(THEME_PACKS.length).toBeGreaterThan(0);
    expect(DEFAULT_THEME_PACK.id).toBe(DEFAULT_THEME_ID);
  });

  it("gives every pack an identifier that is unique and stored-safe", () => {
    // These strings are written into `tenant_settings.selected_theme_id`, whose CHECK accepts this
    // shape and nothing else. A pack whose id the database would refuse could be chosen on screen
    // and then fail to save, which is the worst place to find out.
    const ids = THEME_PACKS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) {
      expect(id).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
    }
  });

  it("never retires the default", () => {
    // Retiring it would leave every temple that has not chosen — and every temple whose choice no
    // longer resolves — pointing at a pack the picker refuses to show.
    expect(DEFAULT_THEME_PACK.retired).toBeFalsy();
  });

  it("resolves an unknown, absent or empty choice to the default", () => {
    expect(themePackById(null).id).toBe(DEFAULT_THEME_ID);
    expect(themePackById(undefined).id).toBe(DEFAULT_THEME_ID);
    expect(themePackById("").id).toBe(DEFAULT_THEME_ID);
    // What a pack removed outright rather than retired leaves behind.
    expect(themePackById("a-pack-that-was-deleted").id).toBe(DEFAULT_THEME_ID);
  });

  it("gives every pack a name and a sentence", () => {
    for (const pack of THEME_PACKS) {
      expect(pack.name.trim().length).toBeGreaterThan(0);
      expect(pack.description.trim().length).toBeGreaterThan(0);
    }
  });
});

describe.each(THEME_PACKS.map((p) => [p.name, p] as const))("%s", (_name, pack) => {
  it("supplies a value for every role, and nothing but roles", () => {
    const roles = Object.keys(pack.palette).sort();
    expect(roles).toEqual([...THEME_TOKENS].sort());
  });

  it("writes every role as a six-digit hex", () => {
    // Anything else — a named colour, a three-digit shorthand, an rgb() string — is skipped
    // silently by the converter, leaving that one surface on the previous palette.
    const wrong = THEME_TOKENS.filter((t) => hexToChannels(pack.palette[t]) === null);
    expect(wrong).toEqual([]);
  });

  it("clears all thirty-nine pairings the interface actually makes", () => {
    const palette: ThemePalette = pack.palette;
    const failures = REQUIRED.filter(([a, b, floor]) => ratio(palette[a], palette[b]) < floor).map(
      ([a, b, floor]) =>
        `${a} on ${b}: ${ratio(palette[a], palette[b]).toFixed(2)} (floor ${floor})`
    );
    expect(failures).toEqual([]);
  });
});

describe("the palette compiled into the stylesheet", () => {
  it("is the default pack, to the channel", () => {
    // `globals.css` carries a copy of the default palette so that a signed-out screen and the very
    // first paint of every session have colours before any JavaScript runs. A copy is a copy: this
    // is what stops it drifting from the pack it is supposed to be.
    const css = fs.readFileSync(
      path.resolve(__dirname, "..", "app", "globals.css"),
      "utf8"
    );
    const root = /:root\s*\{([^}]*)\}/.exec(css);
    expect(root, "globals.css has no :root block").not.toBeNull();

    const compiled: Record<string, string> = {};
    for (const line of root![1].split("\n")) {
      const declaration = /^\s*--kms-([a-z-]+)\s*:\s*([\d\s]+);/.exec(line);
      if (declaration) {
        compiled[declaration[1]] = declaration[2].trim();
      }
    }

    const expected = Object.fromEntries(
      THEME_TOKENS.map((t) => [t, hexToChannels(DEFAULT_THEME_PACK.palette[t])])
    );
    expect(compiled).toEqual(expected);
  });
});

describe("the contrast arithmetic", () => {
  it("agrees with the values the build tool recorded", () => {
    // Two independent implementations of the same 2008 formula, one here and one in
    // tools/theme/build_theme_pack.py. These figures are the ones the tool printed when the packs
    // were built, so a drift in either copy shows up as a failure here rather than as a pack that
    // passes one checker and fails the other.
    expect(ratio("#2B2621", "#FFFFFF")).toBeCloseTo(14.98, 1);
    expect(ratio("#AE5838", "#FCF8F5")).toBeCloseTo(4.68, 1);
    expect(ratio("#716B65", "#F1EDEB")).toBeCloseTo(4.52, 1);
    // The focus ring as it was, and as it is. The first is the defect this token exists for.
    expect(ratio("#ECD9CF", "#FFFFFF")).toBeCloseTo(1.36, 1);
    expect(ratio("#BE775E", "#FFFFFF")).toBeCloseTo(3.51, 1);
  });
});
