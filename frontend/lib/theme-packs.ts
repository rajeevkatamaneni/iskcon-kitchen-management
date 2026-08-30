import type { ThemeFamily, ThemePalette } from "./theme";

/**
 * The catalogue. Every colour scheme a temple can choose between, and the only place they live.
 *
 * <p><b>Why a file and not a table.</b> Nothing ever writes a theme pack at run time. A pack is
 * produced by `tools/theme/build_theme_pack.py`, contrast-checked, reviewed and shipped. No person
 * types one into a form and no request creates one — so a table would be code wearing a table's
 * clothes, and would drag in a migration per change, a policy set, a service, an endpoint, a
 * permission and an operator screen to administer sixteen rows that change only when somebody
 * deploys. The first version of this feature did exactly that and was replaced before it ran
 * anywhere. See V72 for the longer version.
 *
 * <p>Three things follow from the packs being here, and all three are improvements. The browser
 * needs no request to learn a palette, because it already holds every one of them — only the
 * chosen identifier travels, on `/whoami`. Correcting a pack becomes a code change and a deploy,
 * which is what it is, and every temple wearing that pack gets the correction. And the contrast
 * contract stops being "all the keys are present", which is all a database constraint could
 * manage, and becomes the thirty-four pairings checked by `npm test` on every commit.
 *
 * <p><b>Adding one.</b> Run the build tool, paste what it prints, put it in its family. It will
 * not print anything that fails a pairing.
 *
 * <p><b>Removing one.</b> Don't — retire it. See {@link ThemePack.retired}.
 */

export interface ThemePack {
  /**
   * Stable, and never renamed once shipped: this is the string stored against every temple that
   * chose this pack, and renaming it would silently return all of them to the default.
   */
  id: string;
  name: string;
  family: ThemeFamily;
  /** One sentence saying what it feels like. A palette cannot be judged from a swatch grid. */
  description: string;
  /**
   * Withdrawn: still resolves for temples already on it, no longer offered to anybody choosing.
   *
   * <p>This exists so that removing a pack outright almost never has to happen. Deleting is rarely
   * the right operation on a colour scheme — if a pack is wrong, its colours get corrected here and
   * every temple wearing it is corrected with them. When one genuinely has to go, retiring it means
   * nobody's screens change underneath them on a Tuesday morning.
   */
  retired?: boolean;
  palette: ThemePalette;
}

/**
 * What a temple wears before it chooses, and what an unrecognised choice falls back to.
 *
 * <p>Named rather than "the first one in the list" on purpose. The picker is ordered bright to
 * quiet, so the first entry is whichever vibrant pack happens to sort first — and tying the default
 * to that position would mean reordering the picker for presentation silently repainted every
 * temple that has never chosen. Two decisions, two places.
 */
export const DEFAULT_THEME_ID = "temple-terracotta";

export const THEME_PACKS: ThemePack[] = [
  {
    id: "peacock",
    name: "Peacock",
    family: "VIBRANT",
    description:
      "The blue and green of a peacock feather — the mark Krishna wears in his crown. Bright, and unmistakably not the terracotta.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F1FDFF",
      "sunken": "#E4F4F7",
      "hairline": "#D8E7EA",
      "hairline-strong": "#C9D8DA",
      "ink": "#1A3236",
      "ink-secondary": "#4C6163",
      "ink-muted": "#607173",
      "ink-inverse": "#F0FDFF",
      "accent-bg": "#D1F9FE",
      "accent-border": "#96F1FD",
      "accent": "#187985",
      "accent-hover": "#166D77",
      "accent-text": "#107A85",
      "focus-ring": "#1899A7",
      "danger-bg": "#FFE6E3",
      "danger": "#BF3B35",
      "info-bg": "#DBF1FE",
      "info": "#19739D",
      "warning-bg": "#FFEBC2",
      "warning": "#876611",
      "success-bg": "#CDFAD5",
      "success": "#1A7D3D",
    },
  },
  {
    id: DEFAULT_THEME_ID,
    name: "Temple terracotta",
    family: "MUTED",
    description:
      "Softened terracotta on warm grey, drawn from ISKCON’s own saffron. The palette this application was designed in.",
    palette: {
      // Warm-grey neutrals — near-neutral, a hair warm so they sit under terracotta without
      // reading as cream.
      canvas: "#FFFFFF",
      raised: "#FAF8F7",
      sunken: "#F1EDEB",
      hairline: "#E7E1DD",
      "hairline-strong": "#DAD1CB",
      // Warm charcoal, never pure black: a trace of warmth ties the text to the accent.
      ink: "#2B2621",
      "ink-secondary": "#6E6660",
      // Darkened 2026-08-20 from #9C948C, which failed AA on all three surfaces — 2.99 on canvas,
      // 2.82 on raised, 2.57 on sunken. "Muted" was being read as "faint".
      "ink-muted": "#716B65",
      "ink-inverse": "#FCF8F5",
      "accent-bg": "#F6EBE4",
      "accent-border": "#ECD9CF",
      // Darkened 2026-08-21 from #BE6444: the primary button sets ink-inverse on this fill and
      // that pair measured 3.90:1, under the 4.5 AA asks of button text. This measures 4.68:1.
      accent: "#AE5838",
      "accent-hover": "#94482D",
      "accent-text": "#8A4A2F",
      // Its own role since 2026-08-28. It had been borrowing accent-border, which measured 1.36:1
      // against the page — the only focus indicator in the application, effectively invisible,
      // against the 3:1 WCAG 2.2 asks. This is the lightest terracotta clearing the floor on all
      // three surfaces.
      "focus-ring": "#BE775E",
      "danger-bg": "#F7E7E3",
      danger: "#9B2C1F",
      "info-bg": "#EDF7FC",
      info: "#356780",
      // Gold rather than orange, so it never reads as the accent.
      "warning-bg": "#F4EAD1",
      warning: "#87641A",
      "success-bg": "#E7EFE8",
      success: "#3E6B48",
    },
  },
];

/** The default, which every other lookup falls back to. Its absence is a programming error. */
export const DEFAULT_THEME_PACK: ThemePack = (() => {
  const found = THEME_PACKS.find((p) => p.id === DEFAULT_THEME_ID);
  if (!found) {
    throw new Error(`The default theme ${DEFAULT_THEME_ID} is not in the catalogue`);
  }
  return found;
})();

/**
 * The pack a stored identifier refers to, or the default.
 *
 * <p>Three things arrive here and all three mean the default: a temple that has never chosen
 * (null), a platform operator who belongs to no temple (also null), and an identifier that no
 * longer matches anything, which is what a pack removed outright rather than retired leaves
 * behind. The last is the reason this never returns null — a temple in that position should see
 * the application's own colours and be able to choose again, not a half-painted screen.
 */
export function themePackById(id: string | null | undefined): ThemePack {
  if (!id) {
    return DEFAULT_THEME_PACK;
  }
  return THEME_PACKS.find((p) => p.id === id) ?? DEFAULT_THEME_PACK;
}

/**
 * What to offer somebody choosing: everything except the retired ones.
 *
 * <p>With one exception — a pack a temple is currently wearing stays on the list even once it is
 * retired. Otherwise the picker would show nothing selected and the administrator would be told,
 * in effect, that their temple is wearing a colour that does not exist.
 */
export function choosableThemePacks(currentId: string | null | undefined): ThemePack[] {
  return THEME_PACKS.filter((p) => !p.retired || p.id === currentId);
}
