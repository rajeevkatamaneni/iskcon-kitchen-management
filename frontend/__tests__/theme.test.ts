import { describe, expect, it } from "vitest";
import {
  applyPalette,
  cssVariableName,
  hexToChannels,
  isCompletePalette,
  paletteToCssText,
  SURFACE_TOKENS,
  THEME_PREPAINT_SCRIPT,
  THEME_TOKENS,
  type ThemePalette,
} from "@/lib/theme";
import { DEFAULT_THEME_PACK } from "@/lib/theme-packs";

/** The palette the application falls back to, which is the default pack's. */
const DEFAULT_PALETTE = DEFAULT_THEME_PACK.palette;

/**
 * The token contract, and the two conversions everything else depends on.
 *
 * <p>What is worth asserting here is narrow but load-bearing. A theme pack that is missing one
 * role, or that carries a hex the converter cannot read, does not fail loudly — it paints most of
 * a screen and leaves one surface behind, which looks like a rendering bug rather than a data one
 * and is exactly the sort of thing that reaches a temple before it reaches a test.
 */

describe("the token contract", () => {
  it("names every role exactly once", () => {
    expect(new Set(THEME_TOKENS).size).toBe(THEME_TOKENS.length);
  });

  it("prefixes every custom property so nothing else in the page can collide with it", () => {
    expect(THEME_TOKENS.map(cssVariableName).every((n) => n.startsWith("--kms-"))).toBe(true);
  });
});

describe("hex to channels", () => {
  it("splits a colour into the three numbers Tailwind's opacity modifier needs", () => {
    // Not cosmetic. `bg-raised/60` compiles to `rgb(var(--kms-raised) / 0.6)`, and a variable
    // holding "#FAF8F7" makes that declaration invalid — the surface vanishes rather than fading.
    expect(hexToChannels("#AE5838")).toBe("174 88 56");
    expect(hexToChannels("#FFFFFF")).toBe("255 255 255");
    expect(hexToChannels("#000000")).toBe("0 0 0");
  });

  it("is not fooled by case or by surrounding space", () => {
    expect(hexToChannels("  #ae5838 ")).toBe("174 88 56");
  });

  it("refuses anything that is not a six-digit hex", () => {
    for (const bad of ["#FFF", "AE5838", "rgb(1,2,3)", "tomato", "", "#GGGGGG", "#AE58388"]) {
      expect(hexToChannels(bad)).toBeNull();
    }
  });
});

describe("a palette on the page", () => {
  it("writes one declaration per role", () => {
    const css = paletteToCssText(DEFAULT_PALETTE);
    for (const token of THEME_TOKENS) {
      expect(css).toContain(`--kms-${token}:`);
    }
  });

  it("leaves a role alone rather than writing a broken value for it", () => {
    // A partial pack degrades one surface at a time to the compiled default. The alternative —
    // writing `rgb(undefined)` — blanks the surface entirely.
    const broken = { ...DEFAULT_PALETTE, accent: "not a colour" } as ThemePalette;
    const css = paletteToCssText(broken);
    expect(css).not.toContain("--kms-accent:");
    expect(css).toContain("--kms-accent-hover:");
  });

  it("clears the last pack's colours when a new one does not carry them", () => {
    // The bug this prevents: switch pack, and one token from the previous one stays on the element
    // because nothing removed it. Every role is written or removed on every apply.
    const element = document.createElement("div");
    applyPalette(element, DEFAULT_PALETTE);
    // Derived, not typed in: this used to hardcode the default pack's accent and broke the day the
    // catalogue was replaced, which told us nothing about the code under test.
    expect(element.style.getPropertyValue("--kms-accent")).toBe(
      hexToChannels(DEFAULT_PALETTE.accent)
    );

    applyPalette(element, { accent: "#0B7F81" });
    expect(element.style.getPropertyValue("--kms-accent")).toBe("11 127 129");
    expect(element.style.getPropertyValue("--kms-canvas")).toBe("");
  });

  it("takes everything off again when there is no pack", () => {
    const element = document.createElement("div");
    applyPalette(element, DEFAULT_PALETTE);
    applyPalette(element, null);
    for (const token of THEME_TOKENS) {
      expect(element.style.getPropertyValue(cssVariableName(token))).toBe("");
    }
  });
});

describe("surface treatment", () => {
  it("writes a pack's shadows and gradient verbatim, because they are already CSS", () => {
    const element = document.createElement("div");
    applyPalette(element, DEFAULT_PALETTE, {
      "shadow-card": "0 1px 3px rgba(0,0,0,0.1)",
      "accent-gradient": "linear-gradient(180deg, #D2393B 0%, #B22E30 100%)",
      "surface-blur": "12px",
    });

    expect(element.style.getPropertyValue("--kms-shadow-card")).toBe("0 1px 3px rgba(0,0,0,0.1)");
    expect(element.style.getPropertyValue("--kms-accent-gradient")).toBe(
      "linear-gradient(180deg, #D2393B 0%, #B22E30 100%)"
    );
    expect(element.style.getPropertyValue("--kms-surface-blur")).toBe("12px");
  });

  it("puts a flat pack back to flat rather than leaving the last one's shadows on", () => {
    // The same reason every colour is written or removed rather than only written: switch from a
    // glossy pack to a plain one and the gloss has to go with it.
    const element = document.createElement("div");
    applyPalette(element, DEFAULT_PALETTE, { "shadow-card": "0 1px 3px rgba(0,0,0,0.1)" });
    applyPalette(element, DEFAULT_PALETTE, null);

    for (const token of SURFACE_TOKENS) {
      expect(element.style.getPropertyValue(cssVariableName(token))).toBe("");
    }
  });

  it("adds nothing a pack did not ask for", () => {
    // Absent means flat. Inventing a shadow to make a theme feel more expensive is the same
    // mistake as ignoring one the designer specified.
    const element = document.createElement("div");
    applyPalette(element, DEFAULT_PALETTE, {});

    for (const token of SURFACE_TOKENS) {
      expect(element.style.getPropertyValue(cssVariableName(token))).toBe("");
    }
  });
});

describe("completeness", () => {
  it("accepts the palette the application ships with", () => {
    expect(isCompletePalette(DEFAULT_PALETTE)).toBe(true);
  });

  it("rejects a pack that is missing a role", () => {
    const { "focus-ring": _dropped, ...rest } = DEFAULT_PALETTE;
    expect(isCompletePalette(rest)).toBe(false);
  });

  it("rejects a pack whose value is not a colour", () => {
    expect(isCompletePalette({ ...DEFAULT_PALETTE, sunken: "#12345" })).toBe(false);
  });

  it("rejects nothing at all", () => {
    expect(isCompletePalette(null)).toBe(false);
    expect(isCompletePalette(undefined)).toBe(false);
  });
});

describe("the script that runs before the first frame", () => {
  it("does the same conversion the application does", () => {
    // It cannot import anything — it is a string inlined into the document — so it carries its own
    // copy of the hex-to-channel arithmetic. This is the check that the copy has not drifted.
    const store: Record<string, string> = {
      "kms.theme": JSON.stringify({
        tenantId: "t1",
        slug: "peacock",
        palette: { accent: "#0B7F81", canvas: "#FFFFFF", broken: "nope" },
        surfaces: { "shadow-card": "0 1px 3px rgba(0,0,0,0.1)" },
      }),
    };
    const root = document.createElement("div");
    const localStorage = { getItem: (k: string) => store[k] ?? null };
    const document_ = { documentElement: root };

    new Function("localStorage", "document", THEME_PREPAINT_SCRIPT)(localStorage, document_);

    expect(root.style.getPropertyValue("--kms-accent")).toBe("11 127 129");
    expect(root.style.getPropertyValue("--kms-canvas")).toBe("255 255 255");
    // A value it cannot read is skipped, not written as rubbish.
    expect(root.style.getPropertyValue("--kms-broken")).toBe("");
    // Surfaces come through the pre-paint too, or the first frame is flat and then gains shadows.
    expect(root.style.getPropertyValue("--kms-shadow-card")).toBe("0 1px 3px rgba(0,0,0,0.1)");
  });

  it("does nothing at all when there is no cache, and never throws", () => {
    const root = document.createElement("div");
    const empty = { getItem: () => null };
    expect(() =>
      new Function("localStorage", "document", THEME_PREPAINT_SCRIPT)(empty, {
        documentElement: root,
      })
    ).not.toThrow();
    expect(root.getAttribute("style")).toBeNull();
  });

  it("survives a browser that refuses to hand over storage at all", () => {
    // Not hypothetical: reading localStorage throws outright when a browser is set to block site
    // data, and this script runs before any error handling exists.
    const hostile = {
      getItem() {
        throw new Error("The operation is insecure.");
      },
    };
    expect(() =>
      new Function("localStorage", "document", THEME_PREPAINT_SCRIPT)(hostile, {
        documentElement: document.createElement("div"),
      })
    ).not.toThrow();
  });

  it("ignores a cache somebody has scribbled in", () => {
    const store: Record<string, string> = { "kms.theme": "{not json" };
    expect(() =>
      new Function("localStorage", "document", THEME_PREPAINT_SCRIPT)(
        { getItem: (k: string) => store[k] ?? null },
        { documentElement: document.createElement("div") }
      )
    ).not.toThrow();
  });
});
