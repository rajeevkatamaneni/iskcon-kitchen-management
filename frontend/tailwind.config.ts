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
        canvas: "#FFFFFF",
        raised: "#FBF9F6",
        sunken: "#F5F1EA",

        hairline: {
          DEFAULT: "#EDE8DF",
          strong: "#E0D9CC",
        },

        ink: {
          DEFAULT: "#231A08",
          secondary: "#71695F",
          muted: "#948C82",
          inverse: "#FBF9F6",
        },

        // One job only: the primary action on a screen, the active nav item, focus
        // rings. If olive appears anywhere that isn't the main thing to do here, that
        // is a bug — see the "one colour doing four jobs" anti-pattern.
        accent: {
          bg: "#EEF0E4",
          border: "#D8DCC4",
          DEFAULT: "#505530",
          hover: "#3F4326",
          text: "#3A3E23",
        },

        // Status only, never decorative. If one of these appears, something is
        // genuinely low, wrong, overdue, or complete.
        danger: { bg: "#F7E7E3", DEFAULT: "#9B2C1F" },
        warning: { bg: "#F6E7DC", DEFAULT: "#9B4A1F" },
        success: { bg: "#E7EFE4", DEFAULT: "#3F6B41" },
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
        focus: "0 0 0 3px #D8DCC4",
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
