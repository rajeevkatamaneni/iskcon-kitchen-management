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
          muted: "#9C948C",
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
        warning: { bg: "#F4EAD1", DEFAULT: "#8F6A1C" },
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
