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
  /** The resting elevation of a card or panel. */
  "shadow-card",
  /** The same surface under the pointer, or while it is being pressed. */
  "shadow-raised",
  /** A modal, a popover, a panel floating over the page. */
  "shadow-overlay",
  /** The primary fill. `none` leaves the flat `accent` colour showing. */
  "accent-gradient",
  /** Backdrop blur behind an overlay or a sticky header. `0` for none. */
  "surface-blur",
] as const;

export type SurfaceToken = (typeof SURFACE_TOKENS)[number];

/** What a pack renders as where it names no surface treatment at all: nothing. */
export const SURFACE_DEFAULTS: Record<SurfaceToken, string> = {
  "shadow-card": "none",
  "shadow-raised": "none",
  "shadow-overlay": "none",
  "accent-gradient": "none",
  "surface-blur": "0",
};

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

/** The custom property a token is written to. One place, so the name is never typed twice. */
export function cssVariableName(token: ThemeToken | SurfaceToken): string {
  return `--kms-${token}`;
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
  surfaces: ThemeSurfaces | null = null
) {
  const variables = palette ? paletteToCssVariables(palette) : {};
  for (const token of THEME_TOKENS) {
    const name = cssVariableName(token);
    const value = variables[name];
    if (value) {
      element.style.setProperty(name, value);
    } else {
      element.style.removeProperty(name);
    }
  }

  // Surfaces go on verbatim — they are already CSS and there is nothing to convert. A pack that
  // names none of them is put back to flat rather than left wearing the last pack's shadows, which
  // is the same reason every colour above is written or removed rather than only written.
  for (const token of SURFACE_TOKENS) {
    const name = cssVariableName(token);
    const value = palette && surfaces?.[token];
    if (value) {
      element.style.setProperty(name, value);
    } else {
      element.style.removeProperty(name);
    }
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
if(t&&t.palette){var s=document.documentElement.style;
for(var k in t.palette){var v=/^#([0-9a-f]{6})$/i.exec(t.palette[k]);
if(v){var n=parseInt(v[1],16);s.setProperty("--kms-"+k,((n>>16)&255)+" "+((n>>8)&255)+" "+(n&255));}}
if(t.surfaces){for(var q in t.surfaces){s.setProperty("--kms-"+q,t.surfaces[q]);}}}
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
