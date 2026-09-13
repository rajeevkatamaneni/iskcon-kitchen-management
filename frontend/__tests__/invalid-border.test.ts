import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/**
 * A refused box wears the danger border, whoever built it (T-170).
 *
 * <p>The shared `Form` sets `aria-invalid="true"` on every control it refuses. Only `Field` used to
 * paint the edge red, so a hand-rolled box got a red sentence beside a border that looked fine.
 * One rule in `globals.css` now covers every `input`, `select` and `textarea` carrying the
 * attribute.
 *
 * <p><b>Why a source-level test, and why it is enough.</b> jsdom does not load `globals.css` or
 * compute a cascade from it, so rendering a refused box and asking for its border colour would
 * prove nothing either way. What can go wrong is also exactly what the source shows: the rule is
 * deleted, a selector loses one of the three elements, the value stops using the token
 * `border-danger` uses (so a temple's pack and the rule disagree), or the rule starts touching the
 * width, the outline or the shadow (a refused box grows, or its focus ring goes). Each of those is
 * asserted here. The cascade itself — that 0,1,1 beats a one-class utility — is CSS, not this
 * application, and is not re-proved.
 *
 * <p>The token is read out of `tailwind.config.ts` rather than written into this file, so the test
 * follows `border-danger` if that ever moves rather than pinning a copy of it.
 */

const FRONTEND = path.resolve(__dirname, "..");
const read = (file: string) => fs.readFileSync(path.join(FRONTEND, file), "utf8");
const withoutComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, "");

/** Every innermost `selectors { declarations }` block. `@layer x {` wrappers are skipped because their body holds braces. */
function rules(css: string): { selectors: string[]; body: string }[] {
  const out: { selectors: string[]; body: string }[] = [];
  for (const m of withoutComments(css).matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    out.push({ selectors: m[1].split(",").map((s) => s.trim().replace(/\s+/g, " ")), body: m[2] });
  }
  return out;
}

/** `border-color: …` out of a declaration block, whitespace collapsed, or null. */
function borderColour(body: string): string | null {
  const m = /(?:^|;)\s*border-color\s*:\s*([^;]+?)\s*(?:;|$)/.exec(body);
  return m ? m[1].replace(/\s+/g, " ") : null;
}

/** What Tailwind's `border-danger` resolves to, from the config, at full opacity. */
function borderDangerColour(): string {
  const config = read("tailwind.config.ts");
  const danger = /\bdanger\s*:\s*\{[^}]*?\bDEFAULT\s*:\s*"([^"]+)"/.exec(config);
  expect(danger, "tailwind.config.ts has no danger.DEFAULT colour").not.toBeNull();
  // Tailwind compiles `border-danger` with `--tw-border-opacity: 1` in place of `<alpha-value>`.
  return danger![1].replace("<alpha-value>", "1").replace(/\s+/g, " ");
}

const REFUSED = /^(input|select|textarea)\[aria-invalid=(["'])true\2\]$/;

describe("a refused box's border", () => {
  const css = read("app/globals.css");
  const refusedRules = rules(css).filter((r) => r.selectors.some((s) => REFUSED.test(s)));

  it("border-danger resolves through the theme's channel token, so a pack change reaches it", () => {
    // If this ever stops naming a --kms-* token, the rule below would be pinned to a copy of a
    // colour and stop following the temple's pack. Checked here so that failure is named.
    expect(borderDangerColour()).toMatch(/^rgb\(var\(--kms-danger\) \/ 1\)$/);
  });

  it("is painted for input, select and textarea alike", () => {
    const covered = new Set(
      refusedRules.flatMap((r) => r.selectors).map((s) => REFUSED.exec(s)?.[1]).filter(Boolean)
    );
    expect([...covered].sort()).toEqual(["input", "select", "textarea"]);
  });

  it("in the same colour as border-danger, so a Field in error has one red and not two", () => {
    const expected = borderDangerColour();
    for (const element of ["input", "select", "textarea"]) {
      const painted = refusedRules
        .filter((r) => r.selectors.some((s) => REFUSED.exec(s)?.[1] === element))
        .map((r) => borderColour(r.body))
        .filter((c): c is string => c !== null);
      expect(painted, `${element}[aria-invalid="true"] has no border-color`).not.toEqual([]);
      for (const colour of painted) expect(colour, element).toBe(expected);
    }
  });

  it("changes only the colour: no width, style, outline or shadow, so nothing grows and focus stays visible", () => {
    expect(refusedRules).not.toEqual([]);
    for (const rule of refusedRules) {
      const properties = rule.body
        .split(";")
        .map((d) => d.split(":")[0].trim())
        .filter(Boolean);
      expect(properties).toEqual(["border-color"]);
    }
  });

  it("is written only for a box marked true, never for one that is fine", () => {
    // Field writes aria-invalid="false" on a box with no error. A selector such as `[aria-invalid]`
    // would paint every Field on every form red.
    for (const rule of rules(css)) {
      for (const selector of rule.selectors) {
        if (/aria-invalid/.test(selector)) expect(selector).toMatch(REFUSED);
      }
    }
  });
});
