import type { ThemeFamily, ThemePalette, ThemeSurfaces } from "./theme";

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
  /**
   * Shadows, a gradient and a blur, exactly as the pack specified them.
   *
   * <p>Optional, and absent means flat. These are raw CSS applied verbatim: we do not tune them,
   * derive missing ones, or invent a shadow to make a pack feel more expensive. A pack is the
   * designer's work and it ships as their work.
   */
  surfaces?: ThemeSurfaces;
}

/**
 * What a temple wears before it chooses, and what an unrecognised choice falls back to.
 *
 * <p>Named rather than "the first one in the list" on purpose. The picker is ordered bright to
 * quiet, so the first entry is whichever vibrant pack happens to sort first — and tying the default
 * to that position would mean reordering the picker for presentation silently repainted every
 * temple that has never chosen. Two decisions, two places.
 */
export const DEFAULT_THEME_ID = "terracotta";

export const THEME_PACKS: ThemePack[] = [
  {
    id: "kumkum",
    name: "Kumkum",
    family: "VIBRANT",
    description:
      "Vermilion at full strength on a warm rose page, with cool porcelain cards standing off it, loud through saturation rather than gloss.",
    palette: {
      "canvas": "#FFDDD7",
      "raised": "#FAFDFF",
      "sunken": "#D3DAE1",
      "hairline": "#C5CCD2",
      "hairline-strong": "#B8C0C7",
      "ink": "#2A2E33",
      "ink-secondary": "#45484C",
      "ink-muted": "#595C60",
      "ink-inverse": "#FCFEFF",
      "accent-bg": "#FFC5BB",
      "accent-border": "#FFA494",
      "accent": "#DA2D1D",
      "accent-hover": "#C51304",
      "accent-text": "#B20C01",
      "focus-ring": "#D64030",
      "danger-bg": "#FFC7C0",
      "danger": "#A62B27",
      "info-bg": "#B5DCFF",
      "info": "#015F99",
      "warning-bg": "#EED495",
      "warning": "#715700",
      "success-bg": "#B1E7BB",
      "success": "#036B2F",
      "meter-low": "#CD493E",
      "meter-mid": "#937204",
      "meter-high": "#1C8742",
      "meter-pledged": "#AC6155",
      "meter-neutral": "#6C7781",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(42,46,51,0.1), 0 2px 6px rgba(42,46,51,0.12)",
      "shadow-raised": "0 4px 10px rgba(42,46,51,0.14), 0 2px 4px rgba(42,46,51,0.1)",
      "shadow-overlay": "0 22px 44px rgba(42,46,51,0.24), 0 4px 10px rgba(42,46,51,0.12)",
      "accent-gradient": "linear-gradient(180deg, #DA2D1D 0%, #C51304 100%)",
      "surface-blur": "14px",
    },
  },
  {
    id: "tulsi",
    name: "Tulsi",
    family: "VIBRANT",
    description:
      "A living green page under a louder green button, with ivory cards, as if the screen were standing in the tulsi courtyard at midday.",
    palette: {
      "canvas": "#C5F5C7",
      "raised": "#FFFCF6",
      "sunken": "#E0D8CC",
      "hairline": "#D5CEC2",
      "hairline-strong": "#CAC2B5",
      "ink": "#322D25",
      "ink-secondary": "#4B4742",
      "ink-muted": "#5F5B55",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#9EEBA3",
      "accent-border": "#80D787",
      "accent": "#028429",
      "accent-hover": "#017221",
      "accent-text": "#046B20",
      "focus-ring": "#00892A",
      "danger-bg": "#FFC7C0",
      "danger": "#A62B27",
      "info-bg": "#B5DCFF",
      "info": "#015F99",
      "warning-bg": "#F2D396",
      "warning": "#745501",
      "success-bg": "#B1E7BB",
      "success": "#036B2F",
      "meter-low": "#CD493E",
      "meter-mid": "#977006",
      "meter-high": "#1C8742",
      "meter-pledged": "#49824E",
      "meter-neutral": "#7C7569",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(50,45,37,0.1), 0 2px 6px rgba(50,45,37,0.12)",
      "shadow-raised": "0 4px 10px rgba(50,45,37,0.14), 0 2px 4px rgba(50,45,37,0.1)",
      "shadow-overlay": "0 22px 44px rgba(50,45,37,0.24), 0 4px 10px rgba(50,45,37,0.12)",
      "accent-gradient": "linear-gradient(180deg, #028429 0%, #017221 100%)",
      "surface-blur": "14px",
    },
  },
  {
    id: "yamuna",
    name: "Yamuna",
    family: "VIBRANT",
    description:
      "Deep river blue on a tinted sky page with warm ivory cards, bright enough to survive a monitor sitting next to a window.",
    palette: {
      "canvas": "#D8E8FF",
      "raised": "#FFFCF6",
      "sunken": "#E0D8CC",
      "hairline": "#D3CCC0",
      "hairline-strong": "#C8C0B2",
      "ink": "#322D25",
      "ink-secondary": "#4B4742",
      "ink-muted": "#5F5B55",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#BCD7FF",
      "accent-border": "#98C1FF",
      "accent": "#016DEA",
      "accent-hover": "#015ECC",
      "accent-text": "#0155BA",
      "focus-ring": "#2073E4",
      "danger-bg": "#FFC7C0",
      "danger": "#A62B27",
      "info-bg": "#B5DCFF",
      "info": "#015F99",
      "warning-bg": "#F2D396",
      "warning": "#745501",
      "success-bg": "#B1E7BB",
      "success": "#036B2F",
      "meter-low": "#CD493E",
      "meter-mid": "#977006",
      "meter-high": "#1C8742",
      "meter-pledged": "#5077B1",
      "meter-neutral": "#7C7569",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(50,45,37,0.1), 0 2px 6px rgba(50,45,37,0.12)",
      "shadow-raised": "0 4px 10px rgba(50,45,37,0.14), 0 2px 4px rgba(50,45,37,0.1)",
      "shadow-overlay": "0 22px 44px rgba(50,45,37,0.24), 0 4px 10px rgba(50,45,37,0.12)",
      "accent-gradient": "linear-gradient(180deg, #016DEA 0%, #015ECC 100%)",
      "surface-blur": "14px",
    },
  },
  {
    id: "jamun",
    name: "Jamun",
    family: "VIBRANT",
    description:
      "Ripe jamun purple over a lilac page with cream cards, the most festive pack in the set and the one that reads as celebration.",
    palette: {
      "canvas": "#EFDEFF",
      "raised": "#FFFCF6",
      "sunken": "#E0D8CC",
      "hairline": "#D1CABE",
      "hairline-strong": "#C6BEB0",
      "ink": "#322D25",
      "ink-secondary": "#4B4742",
      "ink-muted": "#5F5B55",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#E4C7FF",
      "accent-border": "#D6A8FF",
      "accent": "#9C4AD7",
      "accent-hover": "#8B37C4",
      "accent-text": "#7E2EB3",
      "focus-ring": "#9C55D1",
      "danger-bg": "#FFC7C0",
      "danger": "#A62B27",
      "info-bg": "#B5DCFF",
      "info": "#015F99",
      "warning-bg": "#F2D396",
      "warning": "#745501",
      "success-bg": "#B1E7BB",
      "success": "#036B2F",
      "meter-low": "#CD493E",
      "meter-mid": "#977006",
      "meter-high": "#1C8742",
      "meter-pledged": "#8A68A7",
      "meter-neutral": "#7C7569",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(50,45,37,0.1), 0 2px 6px rgba(50,45,37,0.12)",
      "shadow-raised": "0 4px 10px rgba(50,45,37,0.14), 0 2px 4px rgba(50,45,37,0.1)",
      "shadow-overlay": "0 22px 44px rgba(50,45,37,0.24), 0 4px 10px rgba(50,45,37,0.12)",
      "accent-gradient": "linear-gradient(180deg, #9C4AD7 0%, #8B37C4 100%)",
      "surface-blur": "14px",
    },
  },
  {
    id: "gulal",
    name: "Gulal",
    family: "VIBRANT",
    description:
      "Festival pink pushed as hard as the contrast floor allows, a page dusted with gulal and cool white cards to keep it legible.",
    palette: {
      "canvas": "#FFDBE9",
      "raised": "#FAFDFF",
      "sunken": "#D3DAE1",
      "hairline": "#C5CBD2",
      "hairline-strong": "#B8C0C7",
      "ink": "#2A2E33",
      "ink-secondary": "#45484C",
      "ink-muted": "#595C60",
      "ink-inverse": "#FCFEFF",
      "accent-bg": "#FFC0DA",
      "accent-border": "#FF9BC8",
      "accent": "#CE2D87",
      "accent-hover": "#BB1076",
      "accent-text": "#A9026A",
      "focus-ring": "#CB3F89",
      "danger-bg": "#FFC7C0",
      "danger": "#A62B27",
      "info-bg": "#B5DCFF",
      "info": "#015F99",
      "warning-bg": "#F2D396",
      "warning": "#745501",
      "success-bg": "#B1E7BB",
      "success": "#036B2F",
      "meter-low": "#CD493E",
      "meter-mid": "#977006",
      "meter-high": "#1C8742",
      "meter-pledged": "#A76080",
      "meter-neutral": "#6C7781",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(42,46,51,0.1), 0 2px 6px rgba(42,46,51,0.12)",
      "shadow-raised": "0 4px 10px rgba(42,46,51,0.14), 0 2px 4px rgba(42,46,51,0.1)",
      "shadow-overlay": "0 22px 44px rgba(42,46,51,0.24), 0 4px 10px rgba(42,46,51,0.12)",
      "accent-gradient": "linear-gradient(180deg, #CE2D87 0%, #BB1076 100%)",
      "surface-blur": "14px",
    },
  },
  {
    id: "clay-lamp",
    name: "Clay Lamp",
    family: "BALANCED",
    description:
      "Warm lamp-lit red on a blush page, colourful in the way a room lit by ghee lamps is colourful.",
    palette: {
      "canvas": "#FFE9E7",
      "raised": "#FCFEFF",
      "sunken": "#DEE4EA",
      "hairline": "#CED4DA",
      "hairline-strong": "#C1C8CF",
      "ink": "#2D3135",
      "ink-secondary": "#4B4E51",
      "ink-muted": "#5F6265",
      "ink-inverse": "#FCFEFF",
      "accent-bg": "#FFD7D2",
      "accent-border": "#FFBAB2",
      "accent": "#B9524B",
      "accent-hover": "#A7413C",
      "accent-text": "#A1423C",
      "focus-ring": "#BF625A",
      "danger-bg": "#FFD7D2",
      "danger": "#AF342F",
      "info-bg": "#CAE6FF",
      "info": "#0366A4",
      "warning-bg": "#F2E1B3",
      "warning": "#785E01",
      "success-bg": "#C7EECE",
      "success": "#097234",
      "meter-low": "#D65145",
      "meter-mid": "#9A7902",
      "meter-high": "#288F4A",
      "meter-pledged": "#B56861",
      "meter-neutral": "#737E88",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(45,49,53,0.06), 0 1px 3px rgba(45,49,53,0.08)",
      "shadow-raised": "0 4px 8px rgba(45,49,53,0.1), 0 2px 4px rgba(45,49,53,0.07)",
      "shadow-overlay": "0 18px 36px rgba(45,49,53,0.16)",
      "accent-gradient": "linear-gradient(180deg, #B9524B 0%, #A7413C 100%)",
      "surface-blur": "8px",
    },
  },
  {
    id: "fern-courtyard",
    name: "Fern Courtyard",
    family: "BALANCED",
    description:
      "A soft green page with a garden accent and ivory cards, which keeps long inventory tables feeling fresh rather than clinical.",
    palette: {
      "canvas": "#DFF8D9",
      "raised": "#FFFDF9",
      "sunken": "#E9E3D7",
      "hairline": "#DCD5CA",
      "hairline-strong": "#D0C9BC",
      "ink": "#343028",
      "ink-secondary": "#514E48",
      "ink-muted": "#65625C",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#C6F0BD",
      "accent-border": "#AFDDA5",
      "accent": "#3E802E",
      "accent-hover": "#2D701C",
      "accent-text": "#327023",
      "focus-ring": "#508B43",
      "danger-bg": "#FFD7D2",
      "danger": "#AF342F",
      "info-bg": "#CAE6FF",
      "info": "#0366A4",
      "warning-bg": "#F6DFB3",
      "warning": "#7D5C04",
      "success-bg": "#C7EECE",
      "success": "#097234",
      "meter-low": "#D65145",
      "meter-mid": "#9F7605",
      "meter-high": "#288F4A",
      "meter-pledged": "#5A894F",
      "meter-neutral": "#837C70",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(52,48,40,0.06), 0 1px 3px rgba(52,48,40,0.08)",
      "shadow-raised": "0 4px 8px rgba(52,48,40,0.1), 0 2px 4px rgba(52,48,40,0.07)",
      "shadow-overlay": "0 18px 36px rgba(52,48,40,0.16)",
      "accent-gradient": "linear-gradient(180deg, #3E802E 0%, #2D701C 100%)",
      "surface-blur": "8px",
    },
  },
  {
    id: "indigo-ledger",
    name: "Glazed Ledger",
    family: "BALANCED",
    description:
      "Glazed tile teal on a pale teal page with cream paper cards, cool and orderly for people who read numbers all morning.",
    palette: {
      "canvas": "#CBFAFA",
      "raised": "#FFFDF9",
      "sunken": "#E9E3D7",
      "hairline": "#DCD6CB",
      "hairline-strong": "#D0C9BC",
      "ink": "#343028",
      "ink-secondary": "#514E48",
      "ink-muted": "#65625C",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#A1F3F3",
      "accent-border": "#82E0E1",
      "accent": "#067F81",
      "accent-hover": "#046D6F",
      "accent-text": "#056F70",
      "focus-ring": "#018B8D",
      "danger-bg": "#FFD7D2",
      "danger": "#AF342F",
      "info-bg": "#CAE6FF",
      "info": "#0366A4",
      "warning-bg": "#F6DFB3",
      "warning": "#7D5C04",
      "success-bg": "#C7EECE",
      "success": "#097234",
      "meter-low": "#D65145",
      "meter-mid": "#9F7605",
      "meter-high": "#288F4A",
      "meter-pledged": "#008B8D",
      "meter-neutral": "#837C70",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(52,48,40,0.06), 0 1px 3px rgba(52,48,40,0.08)",
      "shadow-raised": "0 4px 8px rgba(52,48,40,0.1), 0 2px 4px rgba(52,48,40,0.07)",
      "shadow-overlay": "0 18px 36px rgba(52,48,40,0.16)",
      "accent-gradient": "linear-gradient(180deg, #067F81 0%, #046D6F 100%)",
      "surface-blur": "8px",
    },
  },
  {
    id: "peacock",
    name: "Peacock",
    family: "BALANCED",
    description:
      "The eye of the peacock feather, indigo on a periwinkle page with ivory cards, the green barbule carrying done and bronze carrying care.",
    palette: {
      "canvas": "#EEEDFF",
      "raised": "#FFFDF9",
      "sunken": "#E9E3D7",
      "hairline": "#DAD3C8",
      "hairline-strong": "#CEC7BA",
      "ink": "#343028",
      "ink-secondary": "#514E48",
      "ink-muted": "#65625C",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#DFDDFF",
      "accent-border": "#CAC6FF",
      "accent": "#3B3A8F",
      "accent-hover": "#2C2B72",
      "accent-text": "#4C3D8F",
      "focus-ring": "#786AC7",
      "danger-bg": "#FFD7D2",
      "danger": "#AF342F",
      "info-bg": "#B4EEF5",
      "info": "#056D77",
      "warning-bg": "#FEDBB5",
      "warning": "#865713",
      "success-bg": "#BEEFD8",
      "success": "#007151",
      "meter-low": "#CD5949",
      "meter-mid": "#B26D27",
      "meter-high": "#058F67",
      "meter-pledged": "#A57426",
      "meter-neutral": "#837C70",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(52,48,40,0.06), 0 1px 3px rgba(52,48,40,0.08)",
      "shadow-raised": "0 4px 8px rgba(52,48,40,0.1), 0 2px 4px rgba(52,48,40,0.07)",
      "shadow-overlay": "0 18px 36px rgba(52,48,40,0.16)",
      "accent-gradient": "linear-gradient(180deg, #3B3A8F 0%, #2C2B72 100%)",
      "surface-blur": "8px",
    },
  },
  {
    id: "plum-silk",
    name: "Plum Silk",
    family: "BALANCED",
    description:
      "Soft plum over a warm lilac page, playful in the accents and sober in the surfaces.",
    palette: {
      "canvas": "#FCE7FF",
      "raised": "#FFFDF9",
      "sunken": "#E9E3D7",
      "hairline": "#D9D2C7",
      "hairline-strong": "#CDC6BA",
      "ink": "#343028",
      "ink-secondary": "#514E48",
      "ink-muted": "#65625C",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#F9D3FF",
      "accent-border": "#EABBF1",
      "accent": "#9B59A5",
      "accent-hover": "#8A4994",
      "accent-text": "#85488F",
      "focus-ring": "#A367AC",
      "danger-bg": "#FFD7D2",
      "danger": "#AF342F",
      "info-bg": "#CAE6FF",
      "info": "#0366A4",
      "warning-bg": "#F6DFB3",
      "warning": "#7D5C04",
      "success-bg": "#C7EECE",
      "success": "#097234",
      "meter-low": "#D65145",
      "meter-mid": "#9F7605",
      "meter-high": "#288F4A",
      "meter-pledged": "#9D6CA4",
      "meter-neutral": "#837C70",
    },
    surfaces: {
      "shadow-card": "0 1px 2px rgba(52,48,40,0.06), 0 1px 3px rgba(52,48,40,0.08)",
      "shadow-raised": "0 4px 8px rgba(52,48,40,0.1), 0 2px 4px rgba(52,48,40,0.07)",
      "shadow-overlay": "0 18px 36px rgba(52,48,40,0.16)",
      "accent-gradient": "linear-gradient(180deg, #9B59A5 0%, #8A4994 100%)",
      "surface-blur": "8px",
    },
  },
  {
    id: "terracotta",
    name: "Terracotta",
    family: "MUTED",
    description:
      "The house terracotta, quiet baked earth with colour held back for status and for the thing you can press.",
    palette: {
      "canvas": "#FFF7F4",
      "raised": "#FEFEFF",
      "sunken": "#EAEFF4",
      "hairline": "#D9DEE2",
      "hairline-strong": "#CCD1D6",
      "ink": "#333638",
      "ink-secondary": "#535557",
      "ink-muted": "#67696B",
      "ink-inverse": "#FCFEFF",
      "accent-bg": "#FFE7DC",
      "accent-border": "#F2D3C5",
      "accent": "#9A654C",
      "accent-hover": "#89553D",
      "accent-text": "#8D5C44",
      "focus-ring": "#AA7962",
      "danger-bg": "#FFE4E0",
      "danger": "#B63B35",
      "info-bg": "#DBEEFF",
      "info": "#056BAC",
      "warning-bg": "#F2EBD1",
      "warning": "#7A6500",
      "success-bg": "#DCF2DF",
      "success": "#147739",
      "meter-low": "#D95448",
      "meter-mid": "#9C8201",
      "meter-high": "#319650",
      "meter-pledged": "#B87251",
      "meter-neutral": "#77828C",
    },
    surfaces: {
      "shadow-card": "0 1px 1px rgba(51,54,56,0.04)",
      "shadow-raised": "0 2px 4px rgba(51,54,56,0.07)",
      "shadow-overlay": "0 12px 28px rgba(51,54,56,0.1)",
      "accent-gradient": "none",
      "surface-blur": "0",
    },
  },
  {
    id: "sage-room",
    name: "Sage Room",
    family: "MUTED",
    description:
      "Barely-there sage neutrals for a kitchen office that wants to feel cool and uncluttered.",
    palette: {
      "canvas": "#F3FCF5",
      "raised": "#FFFEFC",
      "sunken": "#F3EEE5",
      "hairline": "#E2DDD5",
      "hairline-strong": "#D6D1C8",
      "ink": "#383530",
      "ink-secondary": "#575551",
      "ink-muted": "#6B6965",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#D9F5E2",
      "accent-border": "#C7E3D0",
      "accent": "#477D5D",
      "accent-hover": "#376D4D",
      "accent-text": "#417355",
      "focus-ring": "#5F8F71",
      "danger-bg": "#FFE4E0",
      "danger": "#B63B35",
      "info-bg": "#DBEEFF",
      "info": "#056BAC",
      "warning-bg": "#F7EAD1",
      "warning": "#846102",
      "success-bg": "#DCF2DF",
      "success": "#147739",
      "meter-low": "#D95448",
      "meter-mid": "#A97E04",
      "meter-high": "#319751",
      "meter-pledged": "#499368",
      "meter-neutral": "#867F73",
    },
    surfaces: {
      "shadow-card": "0 1px 1px rgba(56,53,48,0.04)",
      "shadow-raised": "0 2px 4px rgba(56,53,48,0.07)",
      "shadow-overlay": "0 12px 28px rgba(56,53,48,0.1)",
      "accent-gradient": "none",
      "surface-blur": "0",
    },
  },
  {
    id: "slate-morning",
    name: "Slate Morning",
    family: "MUTED",
    description:
      "Blue-grey daylight on cream paper, the most restrained of the coloured packs and the easiest on tired eyes.",
    palette: {
      "canvas": "#F4FAFF",
      "raised": "#FFFEFC",
      "sunken": "#F3EEE5",
      "hairline": "#E2DDD5",
      "hairline-strong": "#D6D0C7",
      "ink": "#383530",
      "ink-secondary": "#575551",
      "ink-muted": "#6B6965",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#DCEFFF",
      "accent-border": "#C5DFF3",
      "accent": "#46779B",
      "accent-hover": "#36678A",
      "accent-text": "#3E6C8E",
      "focus-ring": "#5D89AB",
      "danger-bg": "#FFE4E0",
      "danger": "#B63B35",
      "info-bg": "#DBEEFF",
      "info": "#056BAC",
      "warning-bg": "#F7EAD1",
      "warning": "#846102",
      "success-bg": "#DCF2DF",
      "success": "#147739",
      "meter-low": "#D95448",
      "meter-mid": "#A97E04",
      "meter-high": "#319751",
      "meter-pledged": "#478ABA",
      "meter-neutral": "#867F73",
    },
    surfaces: {
      "shadow-card": "0 1px 1px rgba(56,53,48,0.04)",
      "shadow-raised": "0 2px 4px rgba(56,53,48,0.07)",
      "shadow-overlay": "0 12px 28px rgba(56,53,48,0.1)",
      "accent-gradient": "none",
      "surface-blur": "0",
    },
  },
  {
    id: "mauve-ash",
    name: "Mauve Ash",
    family: "MUTED",
    description:
      "Ash neutrals with the faintest mauve breath, soft without ever going pink.",
    palette: {
      "canvas": "#FAF8FF",
      "raised": "#FFFEFC",
      "sunken": "#F3EEE5",
      "hairline": "#E2DDD5",
      "hairline-strong": "#D6D0C7",
      "ink": "#383530",
      "ink-secondary": "#575551",
      "ink-muted": "#6B6965",
      "ink-inverse": "#FFFDFA",
      "accent-bg": "#F0E8FF",
      "accent-border": "#DFD5F1",
      "accent": "#7D699B",
      "accent-hover": "#6D598A",
      "accent-text": "#715F8D",
      "focus-ring": "#8E7DAA",
      "danger-bg": "#FFE4E0",
      "danger": "#B63B35",
      "info-bg": "#DBEEFF",
      "info": "#056BAC",
      "warning-bg": "#F7EAD1",
      "warning": "#846102",
      "success-bg": "#DCF2DF",
      "success": "#147739",
      "meter-low": "#D95448",
      "meter-mid": "#A97E04",
      "meter-high": "#319751",
      "meter-pledged": "#9177B8",
      "meter-neutral": "#867F73",
    },
    surfaces: {
      "shadow-card": "0 1px 1px rgba(56,53,48,0.04)",
      "shadow-raised": "0 2px 4px rgba(56,53,48,0.07)",
      "shadow-overlay": "0 12px 28px rgba(56,53,48,0.1)",
      "accent-gradient": "none",
      "surface-blur": "0",
    },
  },
  {
    id: "graphite",
    name: "Graphite",
    family: "MUTED",
    description:
      "True monochrome greys throughout, with copper arriving only on hover and focus and colour otherwise reserved for status and progress.",
    palette: {
      "canvas": "#F8F8F8",
      "raised": "#FEFEFE",
      "sunken": "#EEEEEE",
      "hairline": "#DCDCDC",
      "hairline-strong": "#D0D0D0",
      "ink": "#353535",
      "ink-secondary": "#555555",
      "ink-muted": "#696969",
      "ink-inverse": "#FEFEFE",
      "accent-bg": "#ECECEC",
      "accent-border": "#DADADA",
      "accent": "#4D4D4D",
      "accent-hover": "#87562F",
      "accent-text": "#585858",
      "focus-ring": "#AD7951",
      "danger-bg": "#FFE4E0",
      "danger": "#B63B35",
      "info-bg": "#DBEEFF",
      "info": "#056BAC",
      "warning-bg": "#F4EBD1",
      "warning": "#7D6400",
      "success-bg": "#DCF2DF",
      "success": "#147739",
      "meter-low": "#D95448",
      "meter-mid": "#A08100",
      "meter-high": "#319650",
      "meter-pledged": "#A67B5B",
      "meter-neutral": "#808080",
    },
    surfaces: {
      "shadow-card": "none",
      "shadow-raised": "0 0 0 1px #87562F",
      "shadow-overlay": "0 16px 32px rgba(53,53,53,0.12)",
      "accent-gradient": "none",
      "surface-blur": "0",
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
