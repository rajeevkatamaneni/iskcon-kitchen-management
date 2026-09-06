/**
 * The colour contract between a theme pack and the interface.
 *
 * <p>Every colour in this application resolves through `tailwind.config.ts`, and as of the theme
 * work each of those entries is a CSS custom property rather than a literal. This file names the
 * properties, says what each one is for, and knows how to paint a set of them onto a page. The
 * colours themselves are in `theme-packs.ts`.
 *
 * <p>Two rules the rest of the system depends on:
 *
 * <p><b>The token list is the contract.</b> A theme pack supplies a value for every token here and
 * for nothing else. A pack missing a token would leave that one surface on the default while the
 * rest of the screen moved, which is worse than either palette on its own and much harder to
 * diagnose. `theme-contract.test.ts` holds every pack in the catalogue to this list, and to the
 * thirty-four contrast pairings the interface actually makes.
 *
 * <p><b>Values travel as channels, not as hex.</b> Tailwind's opacity modifier — `bg-raised/60` on
 * every table row, `text-ink/40` on an overlay's scrim — compiles to `rgb(var(--token) / 0.6)`, and
 * that only works if the variable holds `250 248 247` rather than `#FAF8F7`. Forty-six places in
 * the app use one, including one the design-system test requires on every `<tr>` in the codebase.
 */

/**
 * Every themeable colour, in the order the design system introduces them: surfaces, then the lines
 * between them, then text, then the accent, then status.
 *
 * <p>The names are the roles from `docs/DESIGN_SYSTEM.md` §2, unchanged. A theme pack chooses the
 * values; it cannot invent a role, and it cannot decline one.
 */
export const THEME_TOKENS = [
  // Surfaces. Three tones, separated by tone rather than by borders: the page, the things raised
  // off it (cards, panels, the sidebar), and the things sunk into it (inputs, wells, table heads).
  "canvas",
  "raised",
  "sunken",

  // The hairline, for the places where tone alone is not enough, and its emphasised twin.
  "hairline",
  "hairline-strong",

  // Text. Four steps: body, supporting, metadata, and the one that sits on a dark or accent fill.
  "ink",
  "ink-secondary",
  "ink-muted",
  "ink-inverse",

  // The accent, which has one job — the primary action, the active navigation item, focus rings.
  // Five members because that job appears at five weights: a pale wash, a border, the fill, the
  // fill one step darker for hover, and the accent set as text on a pale ground.
  "accent-bg",
  "accent-border",
  "accent",
  "accent-hover",
  "accent-text",

  // The focus ring, which is the only shadow this design system allows and the only thing a
  // keyboard user has to tell them where they are. Its own token as of 2026-08-28: it had been
  // borrowing `accent-border`, whose job is the quiet hairline on a secondary button and of which
  // no contrast is asked. Measured at 1.36:1 against the page, which is to say invisible. See V72.
  "focus-ring",

  // Status, never decorative. Each is a pair: the wash it sits on and the ink written on it.
  "danger-bg",
  "danger",
  "info-bg",
  "info",
  "warning-bg",
  "warning",
  "success-bg",
  "success",

  // Progress meters: fills, never text and never a background. Five rather than one because a bar
  // that is 20% full and one that is 95% full mean different things, and length alone is a poor
  // way to say so at a glance across a room. `pledged` is money promised towards something not yet
  // bought; `neutral` is a proportion that is not a judgement — a share of last month's spend.
  //
  // A meter's floor is 3:1 against `sunken`, the track it sits in. It carries no text, so the only
  // thing it has to do is let somebody see where the bar ends.
  "meter-low",
  "meter-mid",
  "meter-high",
  "meter-pledged",
  "meter-neutral",
] as const;

export type ThemeToken = (typeof THEME_TOKENS)[number];

/**
 * The surface tokens, which hold raw CSS rather than a colour.
 *
 * <p>Added 2026-08-30. Until then a theme was twenty-eight flat colours and everything else — depth,
 * gloss, blur — was fixed by the design system, which said there were no shadows at all. That rule
 * was ours to lift and it has been lifted, because colour alone could not carry the difference we
 * were asking it to: the first fifteen packs separated "bright and vibrant" from "soft and muted"
 * using only how saturated the buttons were, which is a small fraction of a screen.
 *
 * <p>These are custom properties like any other. The mechanism did not need inventing — a CSS
 * variable holds any value, not only a colour — so `--kms-shadow-card` carries a whole box-shadow
 * and `--kms-accent-gradient` a whole gradient. That is deliberate: a designer is not confined to
 * a shape we thought of first, and nothing here has to be parsed to be used.
 *
 * <p>Absent means nothing rather than something. A pack that names none of these renders exactly as
 * it did before — flat, no shadow, no blur — because inventing a shadow the designer did not ask
 * for is the same mistake as ignoring one they did.
 */
export const SURFACE_TOKENS = [
  /** The painted page. `canvas` stays the flat colour that contrast is measured against. */
  "canvas-bg",

  /** What a card, panel, table body or modal is painted with. A gradient glossy, `rgba()` frosted. */
  "surface-card-bg",
  /** A border shorthand, width and style included. Never wrapped in `1px solid`. */
  "surface-card-border",
  /** `backdrop-filter` for the frosted packs; `none` in the other two. */
  "surface-card-backdrop",
  /**
   * The first background *layer* on a surface, never an overlay.
   *
   * <p>THEME-TOKENS §6 is emphatic and gives the reason: an overlay is a sibling of the card's
   * content with `z-index: auto`, so it paints above the text whatever the DOM order — a 0.85-alpha
   * white wash over the first two table rows takes body text from 15:1 to 3.0:1. As a background
   * layer, content always paints above it.
   */
  "surface-card-sheen",

  /** What a `thead` is painted with. Not `sunken` — v1's warm header on a cool page is the bug. */
  "table-header-bg",
  /** Inputs only. They are recessed where buttons are raised, and that difference is deliberate. */
  "input-bg",

  "btn-primary-bg",
  "btn-primary-border",
  "btn-primary-shadow",
  "btn-secondary-bg",
  "btn-secondary-border",
  /** Carries the white top highlight that makes a white button read as raised rather than flat. */
  "btn-secondary-shadow",

  "radius-card",
  "radius-control",

  /** The resting elevation of a card or panel. */
  "shadow-card",
  /** The same surface under the pointer, and dropdowns. */
  "shadow-raised",
  /** A modal, a popover, a panel floating over the page. */
  "shadow-overlay",
  /** The glossy primary-button gradient. Redundant with `btn-primary-bg`; kept from v1. */
  "accent-gradient",
  /** The blur radius alone, where it is wanted apart from `surface-card-backdrop`. */
  "surface-blur",
] as const;

export type SurfaceToken = (typeof SURFACE_TOKENS)[number];

/** What a pack renders as where it names no surface treatment at all: nothing. */
export const SURFACE_DEFAULTS: Record<SurfaceToken, string> = {
  // The floor is flat and opaque: a screen with no pack chosen must look like the application it
  // was designed as, and inventing a gradient nobody asked for is the same mistake as dropping one
  // somebody did. Every value is a complete CSS value, because that is what the token contract says
  // these are — nothing downstream parses or interpolates into one.
  "canvas-bg": "rgb(var(--kms-canvas))",
  "surface-card-bg": "rgb(var(--kms-raised))",
  "surface-card-border": "1px solid rgb(var(--kms-hairline))",
  "surface-card-backdrop": "none",
  "surface-card-sheen": "none",
  "table-header-bg": "rgb(var(--kms-sunken))",
  "input-bg": "rgb(var(--kms-canvas))",
  "btn-primary-bg": "rgb(var(--kms-accent))",
  "btn-primary-border": "1px solid transparent",
  "btn-primary-shadow": "none",
  "btn-secondary-bg": "rgb(var(--kms-canvas))",
  "btn-secondary-border": "1px solid rgb(var(--kms-hairline-strong))",
  "btn-secondary-shadow": "none",
  "radius-card": "0.5rem",
  "radius-control": "0.375rem",
  "shadow-card": "none",
  "shadow-raised": "none",
  "shadow-overlay": "none",
  "accent-gradient": "none",
  "surface-blur": "0",
};

/**
 * The material a pack is made of, and the only key the contract permits branching on.
 *
 * <p>THEME-TOKENS §6: colour saturation alone is not a difference users notice — they compared the
 * three groups and said they looked the same. Glossy, frosted and flat are differences anybody can
 * name, which is why the picker names them.
 */
export const FINISHES = ["glossy", "frosted", "flat"] as const;

export type Finish = (typeof FINISHES)[number];

/** A complete set of colours — one hex value per token, `#RRGGBB`. */
export type ThemePalette = Record<ThemeToken, string>;

/** The surface treatment a pack asks for, if it asks for any. Raw CSS, applied verbatim. */
export type ThemeSurfaces = Partial<Record<SurfaceToken, string>>;

/**
 * How loud a pack is. The three families a temple chooses between, in the words the choice was
 * asked for: bright and vibrant, colourful without being loud, and soft and muted.
 */
export type ThemeFamily = "VIBRANT" | "BALANCED" | "MUTED";

export const THEME_FAMILY_LABELS: Record<ThemeFamily, string> = {
  VIBRANT: "Bright and vibrant",
  BALANCED: "Colourful and calm",
  MUTED: "Soft and muted",
};

/**
 * The material each family is made of, which is the half of the difference people can name.
 *
 * <p>THEME-TOKENS §5 asks for the finish in the group heading, and §6 gives the reason: shown the
 * three families side by side, people said they looked the same, because the only thing separating
 * them was how saturated the buttons were. Nobody can name a saturation difference. Anybody can
 * name glossy, frosted and flat.
 */
export const THEME_FAMILY_FINISH: Record<ThemeFamily, Finish> = {
  VIBRANT: "glossy",
  BALANCED: "frosted",
  MUTED: "flat",
};

/**
 * The custom property a token is written to. One place, so the name is never typed twice.
 *
 * <p>Two names per token, and both are load-bearing.
 *
 * <p>{@link cssVariableName} is the Tailwind bridge: `--kms-canvas` holds space-separated channels
 * because Tailwind compiles `bg-raised/60` to `rgb(var(--kms-raised) / 0.6)`, and a hex string
 * cannot go inside `rgb()`. Forty-six of those opacity modifiers are in the codebase, one of them
 * required by the design-system test on every table row.
 *
 * <p>{@link tokenVariableName} is the contract's own: THEME-TOKENS §2 sets every key as `--<key>`
 * and §4's stylesheet reads `var(--canvas-bg)`, `var(--ink)`, `var(--sunken)` and the rest by those
 * exact names. §4 is quoted verbatim, so those names have to exist.
 *
 * <p>Both are written from the same pack in {@link applyPalette}, so they cannot disagree.
 */
export function cssVariableName(token: ThemeToken | SurfaceToken): string {
  return `--kms-${token}`;
}

/** The contract's own name for a token — `--canvas-bg`, `--ink` — as §2 and §4 spell it. */
export function tokenVariableName(token: ThemeToken | SurfaceToken | "finish"): string {
  return `--${token}`;
}

/**
 * `#AE5838` to `174 88 56` — the space-separated channels Tailwind's opacity modifier needs.
 *
 * <p>Returns null on anything that is not a six-digit hex, so one bad value in a stored pack is
 * skipped rather than writing `rgb(undefined)` into the page and blanking a surface.
 */
export function hexToChannels(hex: string): string | null {
  const match = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!match) {
    return null;
  }
  const n = parseInt(match[1], 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

/**
 * A palette as the declarations that go inside a `:root` block or onto an element's style.
 *
 * <p>Tokens the palette does not carry, or carries badly, are left out — which leaves them at
 * whatever `globals.css` compiled in, so a partial pack degrades to the default one surface at a
 * time rather than rendering an invisible screen.
 */
export function paletteToCssVariables(palette: Partial<ThemePalette>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const token of THEME_TOKENS) {
    const channels = hexToChannels(palette[token] ?? "");
    if (channels) {
      out[cssVariableName(token)] = channels;
    }
  }
  return out;
}

/** The same declarations as a CSS text block, for a `<style>` tag rendered on the server. */
export function paletteToCssText(palette: Partial<ThemePalette>, selector = ":root"): string {
  const declarations = Object.entries(paletteToCssVariables(palette))
    .map(([name, value]) => `${name}:${value}`)
    .join(";");
  return declarations ? `${selector}{${declarations}}` : "";
}

/**
 * Paints a palette onto an element — the document element, in every real use.
 *
 * <p>Every token is written, including the ones the pack got wrong, because this also has to undo
 * the previous pack. Removing a property is how a token returns to the compiled default; leaving it
 * set is how a temple that switches packs ends up wearing one colour from the last one.
 */
export function applyPalette(
  element: HTMLElement,
  palette: Partial<ThemePalette> | null,
  surfaces: ThemeSurfaces | null = null,
  finish: Finish | null = null
) {
  const variables = palette ? paletteToCssVariables(palette) : {};
  for (const token of THEME_TOKENS) {
    const channels = variables[cssVariableName(token)];
    if (channels) {
      element.style.setProperty(cssVariableName(token), channels);
    } else {
      element.style.removeProperty(cssVariableName(token));
    }

    // And the same colour under the contract's own name, as a hex value, because §4 reads
    // `var(--ink)` and `var(--sunken)` directly rather than through Tailwind.
    const hex = palette?.[token];
    if (hex) {
      element.style.setProperty(tokenVariableName(token), hex);
    } else {
      element.style.removeProperty(tokenVariableName(token));
    }
  }

  // Surfaces go on verbatim — they are already complete CSS values and there is nothing to convert.
  //
  // No pack at all removes them, exactly as it removes the colours, so what shows through is the
  // default pack compiled into `globals.css` — which is the whole point of compiling it there. The
  // alternative, writing {@link SURFACE_DEFAULTS} over the top, looks identical today only because
  // the default pack happens to be a flat one: make a glossy pack the default and every screen with
  // no temple behind it — sign-in, an unsubscribe link out of an email — would render flat while
  // the stylesheet said otherwise.
  //
  // A pack that names *some* of them is a different case and does get the floor for the rest,
  // because half a material is worse than none. Under v2 that cannot happen — every surface token
  // is mandatory and `theme-contract.test.ts` holds all fifteen packs to it — but this is the file
  // that has to survive the sixteenth pack somebody adds by hand.
  for (const token of SURFACE_TOKENS) {
    if (!palette) {
      element.style.removeProperty(cssVariableName(token));
      element.style.removeProperty(tokenVariableName(token));
      continue;
    }
    const value = surfaces?.[token] || SURFACE_DEFAULTS[token];
    element.style.setProperty(cssVariableName(token), value);
    element.style.setProperty(tokenVariableName(token), value);
  }

  // §2: the finish is the one key the contract allows code to branch on, and the picker names it.
  if (finish) {
    element.dataset.finish = finish;
  } else {
    delete element.dataset.finish;
  }
}

/**
 * Where the last painted palette is kept, so the next page load does not flash.
 *
 * <p>One key rather than one per temple, holding the tenant it belongs to. The script that reads
 * it runs before anything else on the page and has no way to ask which temple this session is
 * for — that answer arrives with `/whoami`, which is a network round trip away. So what it paints
 * is what this browser painted last, and the provider corrects it a moment later if the answer
 * turns out to be different. Signing out clears it, so the next person at a shared terminal sees
 * the sign-in screen in the application's own colours rather than the last temple's.
 */
export const THEME_CACHE_KEY = "kms.theme";

export interface CachedTheme {
  tenantId: string | null;
  themeId: string;
  palette: ThemePalette;
  surfaces?: ThemeSurfaces;
  finish?: Finish;
}

/**
 * The script that paints the cached palette before the first frame.
 *
 * <p>It runs synchronously, ahead of React, because the alternative is visible: every navigation
 * would render a terracotta screen and then repaint it blue, on every load, forever. That flash is
 * the single most common complaint about themed applications and it is entirely avoidable.
 *
 * <p>Deliberately tiny and deliberately silent. It touches `localStorage`, which throws outright in
 * a browser set to block site data, and it runs before any error handling exists — so everything is
 * inside one try/catch whose failure path is "do nothing", leaving the compiled default in place.
 * There is no token list here on purpose: it writes whatever keys the cached object carries, so
 * adding a role to the contract does not mean remembering to edit a string.
 */
export const THEME_PREPAINT_SCRIPT = `try{
var t=JSON.parse(localStorage.getItem(${JSON.stringify(THEME_CACHE_KEY)})||"null");
if(t&&t.palette){var e=document.documentElement,s=e.style;
for(var k in t.palette){var h=t.palette[k],v=/^#([0-9a-f]{6})$/i.exec(h);
if(v){var n=parseInt(v[1],16);s.setProperty("--kms-"+k,((n>>16)&255)+" "+((n>>8)&255)+" "+(n&255));
s.setProperty("--"+k,h);}}
if(t.surfaces){for(var q in t.surfaces){s.setProperty("--kms-"+q,t.surfaces[q]);s.setProperty("--"+q,t.surfaces[q]);}}
if(t.finish){e.dataset.finish=t.finish;}}
}catch(e){}`;

/**
 * The class that makes a palette swap a crossfade rather than a cut. See `globals.css`.
 *
 * <p>Held on for a little longer than the transition it enables, then taken off — a rule that
 * broad would otherwise override every transition the components choose for themselves.
 */
const THEMING_CLASS = "kms-theming";
const THEMING_MS = 240;
let themingTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * Repaints the page from one palette to the next, and lets the eye follow it across.
 *
 * <p>The timer is module-level and cleared on each call, because somebody comparing packs clicks
 * through five of them in as many seconds. Without that, the first click's timer would strip the
 * class mid-way through the fourth click's fade.
 */
export function crossfadeTheme(run: () => void) {
  const root = document.documentElement;
  root.classList.add(THEMING_CLASS);
  run();
  clearTimeout(themingTimer);
  themingTimer = setTimeout(() => root.classList.remove(THEMING_CLASS), THEMING_MS);
}

/** True when a palette carries a usable value for every token the interface asks for. */
export function isCompletePalette(palette: Partial<ThemePalette> | null | undefined): boolean {
  return !!palette && THEME_TOKENS.every((token) => hexToChannels(palette[token] ?? "") !== null);
}
