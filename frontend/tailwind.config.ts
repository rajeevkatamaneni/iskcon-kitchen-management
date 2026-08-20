import type { Config } from "tailwindcss";

/**
 * Design tokens. See docs/DESIGN_SYSTEM.md for the reasoning behind each choice.
 *
 * Colour values marked "Cocoon" are read from cocoon.com's computed styles — the
 * reference the palette was drawn from. Values marked "extended" are ours, placed on the
 * same warm axis to cover surfaces a marketing site has no need for (inputs, tables).
 *
 * Deliberately no arbitrary values anywhere in the app: inconsistent spacing is a large
 * part of why the interfaces we're designing against feel unconsidered.
 */
const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Warm-grey neutrals — near-neutral, a hair warm so they sit under terracotta without
        // reading as cream. Surfaces separate by tone, not by borders.
        canvas: "#FFFFFF",
        raised: "#FAF8F7",
        sunken: "#F1EDEB",

        hairline: {
          DEFAULT: "#E7E1DD",
          strong: "#DAD1CB",
        },

        // Warm charcoal — text and dark fills. Not pure black; a trace of warmth ties it to the
        // terracotta accent and the warm-grey surfaces.
        ink: {
          DEFAULT: "#2B2621",
          secondary: "#6E6660",
          // Darkened 2026-08-20 from #9C948C, which failed WCAG AA on every surface it was used
          // on — 2.99 on canvas, 2.82 on raised and 2.57 on sunken, against a 4.5 requirement.
          // "Muted" was being read as "faint": hint lines, table metadata and the planner's
          // workforce pebbles were all genuinely hard to read. This is the lightest value that
          // clears 4.5 on the worst of the three (4.52 on sunken, 5.26 on canvas), so it is still
          // plainly the quiet grey — only now it is one somebody can actually read.
          muted: "#716B65",
          inverse: "#FCF8F5",
        },

        // Terracotta — one job only: the primary action on a screen, the active nav item, focus
        // rings. If terracotta appears anywhere that isn't the main thing to do here, that is a
        // bug — see the "one colour doing four jobs" anti-pattern. Softened (desaturated) so it
        // reads flat and calm, never loud.
        accent: {
          bg: "#F6EBE4",
          border: "#ECD9CF",
          DEFAULT: "#BE6444",
          hover: "#A5533A",
          text: "#8A4A2F",
        },

        // Status only, never decorative. If one of these appears, something is genuinely low,
        // wrong, overdue, or complete. Warning is gold, not orange, so it never reads as the
        // terracotta accent.
        danger: { bg: "#F7E7E3", DEFAULT: "#9B2C1F" },
        // Added v1.2 for the Vaishnava calendar: Ekadasi had been wearing the terracotta
        // accent, which this system reserves for the primary action. The pale blue is
        // Rajeev\u2019s; the saturated member sits at the same lightness as success, so the four
        // status colours read as one set.
        info: { bg: "#EDF7FC", DEFAULT: "#356780" },
        // Gold, nudged from #8F6A1C to clear AA on its own wash: it sat at 4.13, just under the
        // 4.5 a badge or a notice needs. The shift is small enough to be invisible beside the old
        // value and is the difference between passing and not.
        warning: { bg: "#F4EAD1", DEFAULT: "#87641A" },
        success: { bg: "#E7EFE8", DEFAULT: "#3E6B48" },
      },

      // One type system across every script we render. Browsers resolve missing
      // glyphs family by family, so an English screen is Anek Latin and a Hindi
      // ingredient name on that same screen is Anek Devanagari — matched by design,
      // not coincidence.
      fontFamily: {
        sans: [
          "var(--font-anek-latin)",
          "var(--font-anek-devanagari)",
          "var(--font-anek-telugu)",
          "var(--font-anek-tamil)",
          "system-ui",
          "sans-serif",
        ],
      },

      fontSize: {
        xs: ["0.75rem", { lineHeight: "1rem" }],
        sm: ["0.875rem", { lineHeight: "1.25rem" }],
        base: ["1rem", { lineHeight: "1.625rem" }],
        lg: ["1.125rem", { lineHeight: "1.75rem" }],
        xl: ["1.375rem", { lineHeight: "1.875rem" }],
        "2xl": ["1.75rem", { lineHeight: "2.25rem", letterSpacing: "-0.02em" }],
        "3xl": ["2.25rem", { lineHeight: "2.75rem", letterSpacing: "-0.02em" }],
      },

      borderRadius: {
        sm: "0.5rem",
        DEFAULT: "0.75rem",
        lg: "1rem",
      },

      // Depth comes from surface tone, not shadow. The only shadow in the system is
      // the focus ring, which is functional.
      boxShadow: {
        none: "none",
        focus: "0 0 0 3px #ECD9CF",
      },

      transitionDuration: {
        state: "150ms",
        enter: "200ms",
      },

      spacing: {
        sidebar: "17.5rem",
      },

      maxWidth: {
        content: "75rem",
        prose: "40rem",
      },

      minHeight: {
        touch: "2.75rem",
      },
      minWidth: {
        touch: "2.75rem",
      },
    },
  },
  plugins: [],
};

export default config;
