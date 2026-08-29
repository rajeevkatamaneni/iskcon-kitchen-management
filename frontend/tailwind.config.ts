import type { Config } from "tailwindcss";

/**
 * Design tokens. See docs/DESIGN_SYSTEM.md for the reasoning behind each choice.
 *
 * Colour is the one thing here a temple chooses. Every colour resolves to a custom property
 * whose value comes from the theme pack that temple has selected, so this file names the roles
 * and nothing else — the values live in `lib/theme.ts` (compiled default) and in the
 * `theme_packs` table (everything else). Nothing below the colours is themeable: type, spacing,
 * radii and motion are decisions about legibility and rhythm, not taste.
 *
 * Deliberately no arbitrary values anywhere in the app: inconsistent spacing is a large
 * part of why the interfaces we're designing against feel unconsidered.
 */
const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      /**
       * Every colour is a custom property, and the values live in a theme pack.
       *
       * See `lib/theme.ts` for the token contract and `app/globals.css` for the values compiled
       * into the stylesheet. What each role is *for* is unchanged and still governed by
       * docs/DESIGN_SYSTEM.md §2 — a temple chooses the values, never the meanings.
       *
       * The `rgb(var(--x) / <alpha-value>)` form rather than a bare `var(--x)`, and the variables
       * therefore hold `250 248 247` rather than `#FAF8F7`. Tailwind's opacity modifier compiles
       * to exactly this shape, and it is used in forty-six places here — including
       * `hover:bg-raised/60`, which the design-system test requires on every table row in the
       * application. A bare var() silently drops the opacity and every one of them would stop
       * working with nothing to show for it.
       */
      colors: {
        canvas: "rgb(var(--kms-canvas) / <alpha-value>)",
        raised: "rgb(var(--kms-raised) / <alpha-value>)",
        sunken: "rgb(var(--kms-sunken) / <alpha-value>)",

        hairline: {
          DEFAULT: "rgb(var(--kms-hairline) / <alpha-value>)",
          strong: "rgb(var(--kms-hairline-strong) / <alpha-value>)",
        },

        ink: {
          DEFAULT: "rgb(var(--kms-ink) / <alpha-value>)",
          secondary: "rgb(var(--kms-ink-secondary) / <alpha-value>)",
          muted: "rgb(var(--kms-ink-muted) / <alpha-value>)",
          inverse: "rgb(var(--kms-ink-inverse) / <alpha-value>)",
        },

        // One job only: the primary action on a screen, the active nav item, the focus ring. If
        // the accent appears anywhere that is not the main thing to do here, that is a bug — see
        // the "one colour doing four jobs" anti-pattern.
        accent: {
          bg: "rgb(var(--kms-accent-bg) / <alpha-value>)",
          border: "rgb(var(--kms-accent-border) / <alpha-value>)",
          DEFAULT: "rgb(var(--kms-accent) / <alpha-value>)",
          hover: "rgb(var(--kms-accent-hover) / <alpha-value>)",
          text: "rgb(var(--kms-accent-text) / <alpha-value>)",
        },

        // Status only, never decorative. If one of these appears, something is genuinely low,
        // wrong, overdue, or complete. Warning is gold, not orange, so it never reads as the
        // accent — and the status hues are fixed across every pack, because red meaning wrong is
        // not a matter of a temple's taste.
        danger: {
          bg: "rgb(var(--kms-danger-bg) / <alpha-value>)",
          DEFAULT: "rgb(var(--kms-danger) / <alpha-value>)",
        },
        info: {
          bg: "rgb(var(--kms-info-bg) / <alpha-value>)",
          DEFAULT: "rgb(var(--kms-info) / <alpha-value>)",
        },
        warning: {
          bg: "rgb(var(--kms-warning-bg) / <alpha-value>)",
          DEFAULT: "rgb(var(--kms-warning) / <alpha-value>)",
        },
        success: {
          bg: "rgb(var(--kms-success-bg) / <alpha-value>)",
          DEFAULT: "rgb(var(--kms-success) / <alpha-value>)",
        },
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

      // The two places this app tracks letters out, named so they stop being typed by hand. The
      // eyebrow was written three ways — 0.06em on the calendar and the planner, 0.08em on the
      // sidebar and Settings — which is the same class of inconsistency as a label written three
      // ways: nobody chose it, fifty pages each did.
      letterSpacing: {
        eyebrow: "0.08em",
        // A value deliberately held at arm's length: a masked key or a PAN, spaced out so the
        // dots read as a count of characters rather than as a word.
        masked: "0.15em",
      },

      // Item 23: the three tracks a row of fields shares — label, control, hint. `auto`, not the
      // `1fr` Tailwind's own grid-rows-3 means, because a track here should be as tall as the
      // tallest thing in it and no taller.
      gridTemplateRows: {
        "field-row": "auto auto auto",
      },

      borderRadius: {
        sm: "0.5rem",
        DEFAULT: "0.75rem",
        lg: "1rem",
      },

      // Depth comes from surface tone, not shadow. The only shadow in the system is
      // the focus ring, which is functional — and which, as of 2026-08-28, has a token of its own
      // rather than borrowing `accent-border`. It was measured at 1.36:1 against the page, under
      // the 3:1 WCAG 2.2 asks of a focus indicator; the two colours had to be separated before it
      // could be raised without putting a dark line around every secondary button. See V72.
      boxShadow: {
        none: "none",
        focus: "0 0 0 3px rgb(var(--kms-focus-ring))",
      },

      transitionDuration: {
        state: "150ms",
        enter: "200ms",
      },

      spacing: {
        sidebar: "17.5rem",
        // Where the text inside an input begins: `px-3` (12px) plus the 1px border every input and
        // every Button variant carries. Not on the spacing scale on purpose — it is not a spacing
        // choice but a measurement of another component, and a label, hint or error indented by it
        // lines up with the words it belongs to rather than with the box's outer edge.
        "field-inset": "0.8125rem",
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
      // The plus on a recipe row, and the gutter reserved for it on a row that has none.
      width: {
        touch: "2.75rem",
      },
    },
  },
  plugins: [],
};

export default config;
