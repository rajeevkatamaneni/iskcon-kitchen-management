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

  // Every `hover:` in the app compiles behind `@media (hover: hover)`. A touch screen fires a
  // hover on tap and then leaves it stuck on the last thing touched, so a tile that lifts on
  // hover would stay lifted after a cook prodded it — and half of this application is used on a
  // phone with floury hands.
  future: { hoverOnlyWhenSupported: true },
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

        // Progress meters. Fills only — never text, never a page background. The names say what a
        // reading means rather than what colour it is, so a theme can answer "what does nearly
        // empty look like" for itself.
        meter: {
          low: "rgb(var(--kms-meter-low) / <alpha-value>)",
          mid: "rgb(var(--kms-meter-mid) / <alpha-value>)",
          high: "rgb(var(--kms-meter-high) / <alpha-value>)",
          pledged: "rgb(var(--kms-meter-pledged) / <alpha-value>)",
          neutral: "rgb(var(--kms-meter-neutral) / <alpha-value>)",
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

      // Depth is a theme's to decide, as of 2026-08-30.
      //
      // It used not to be: the design system said there were no shadows at all and depth came from
      // surface tone. That rule was ours and it has been lifted, because colour alone could not
      // carry the difference between a loud pack and a quiet one — the first fifteen separated the
      // three groups using only how saturated the buttons were.
      //
      // Each of these is whatever the theme pack said, applied verbatim, defaulting to `none` so a
      // pack that asks for no shadow gets no shadow. The focus ring is the exception and always
      // has been: it is functional rather than decorative, so it is built from the palette here
      // rather than left to a pack to forget.
      boxShadow: {
        none: "none",
        focus: "0 0 0 3px rgb(var(--kms-focus-ring))",
        card: "var(--kms-shadow-card, none)",
        raised: "var(--kms-shadow-raised, none)",
        overlay: "var(--kms-shadow-overlay, none)",

        // The one shadow that is not a pack's to define, and the reason it is not: `raised` means
        // "however this pack draws a raised surface", and Graphite draws one as `0 0 0 1px #87562F`
        // — a shadow with no blur and no offset, which paints as a copper ring. That is a fine way
        // to say "raised" about a resting card and a wrong way to say "this came off the page".
        // `lift` is always a blurred drop shadow, built from the pack's own ink so it belongs to
        // the theme, and it cannot be a ring in any of the fifteen.
        lift: "0 4px 10px rgb(var(--kms-ink) / 0.16), 0 1px 3px rgb(var(--kms-ink) / 0.1)",
      },

      // The primary fill, when a pack wants it to be more than one flat colour. `none` leaves the
      // `accent` colour showing through untouched, which is what every pack does today.
      backgroundImage: {
        accent: "var(--kms-accent-gradient, none)",
      },

      backdropBlur: {
        surface: "var(--kms-surface-blur, 0)",
      },

      transitionDuration: {
        /** A press. Short enough that the release is not something you wait for. */
        press: "120ms",
        state: "150ms",
        enter: "200ms",
      },

      /**
       * The two curves this interface moves on.
       *
       * <p>Both are stronger than the browser's built-in `ease-out` and `ease-in-out`, which are
       * weak enough that a 200ms transition reads as a delay rather than as motion. There is
       * deliberately no `ease-in`: it starts slow, which withholds the very moment somebody is
       * watching for, and makes an animation feel slower than a longer one that eases out.
       */
      transitionTimingFunction: {
        out: "cubic-bezier(0.23, 1, 0.32, 1)",
        "in-out": "cubic-bezier(0.77, 0, 0.175, 1)",
      },

      /**
       * How far a control gives under a press, and how far a tile lifts under the pointer.
       *
       * <p>`press` was 0.98 — two per cent of give. Rajeev asked for "10% more squishy so it is
       * more noticable", and ten per cent more of two per cent is 2.2%, which is not visible to
       * anybody. This takes the intent rather than the arithmetic: 0.96 is twice the give and
       * plainly readable as a press, while staying inside the 0.9–0.97 range that keeps a control
       * looking like it was pushed rather than like it collapsed.
       */
      scale: {
        press: "0.96",
      },

      keyframes: {
        /**
         * How a panel over the page arrives: up a little and out of nothing.
         *
         * <p>From 0.97 rather than from 0 — nothing in the world appears from nothing, and a
         * panel that grows from a point reads as a special effect rather than as a thing that was
         * already there.
         */
        "overlay-in": {
          from: { opacity: "0", transform: "scale(0.92)" },
          to: { opacity: "1", transform: "scale(1)" },
        },
        "scrim-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        /** A line of text that was not there a moment ago, arriving without pushing anything. */
        "notice-in": {
          from: { opacity: "0", transform: "translateY(-4px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
      },

      animation: {
        "overlay-in": "overlay-in 240ms cubic-bezier(0.23, 1, 0.32, 1)",
        "scrim-in": "scrim-in 200ms cubic-bezier(0.23, 1, 0.32, 1)",
        "notice-in": "notice-in 200ms cubic-bezier(0.23, 1, 0.32, 1)",
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
