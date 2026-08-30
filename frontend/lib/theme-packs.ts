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
export const DEFAULT_THEME_ID = "terracotta";

export const THEME_PACKS: ThemePack[] = [
  {
    id: "kumkum",
    name: "Kumkum",
    family: "VIBRANT",
    description:
      "Vermilion at full strength, the colour of kumkum on a forehead, carried by saturation rather than gloss.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FFF8F8",
      "sunken": "#FEE9E6",
      "hairline": "#FFDDDA",
      "hairline-strong": "#FEC7C2",
      "ink": "#412D2B",
      "ink-secondary": "#645352",
      "ink-muted": "#736564",
      "ink-inverse": "#FFFDFD",
      "accent-bg": "#FFDDDA",
      "accent-border": "#FFBCB6",
      "accent": "#D2393B",
      "accent-hover": "#BF232A",
      "accent-text": "#C0242B",
      "focus-ring": "#E45451",
      "danger-bg": "#FEE7E3",
      "danger": "#BD3832",
      "info-bg": "#DEEFFF",
      "info": "#0E6DAB",
      "warning-bg": "#FEEAC6",
      "warning": "#87620A",
      "success-bg": "#C2FFCD",
      "success": "#147B3A",
      "meter-low": "#D55F56",
      "meter-mid": "#A97C13",
      "meter-high": "#3B9555",
      "meter-pledged": "#8F8546",
      "meter-neutral": "#91807F",
    },
  },
  {
    id: "tulsi",
    name: "Tulsi",
    family: "VIBRANT",
    description:
      "A loud living green, as if the whole screen were standing in the tulsi courtyard at midday.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F2FEF3",
      "sunken": "#D9F8DC",
      "hairline": "#CBEDCF",
      "hairline-strong": "#B3DFB8",
      "ink": "#28372A",
      "ink-secondary": "#505B51",
      "ink-muted": "#636C64",
      "ink-inverse": "#FAFFFA",
      "accent-bg": "#C5F5CA",
      "accent-border": "#A2E2AA",
      "accent": "#158437",
      "accent-hover": "#001D05",
      "accent-text": "#0B762F",
      "focus-ring": "#139B40",
      "danger-bg": "#FEE7E3",
      "danger": "#BD3832",
      "info-bg": "#DEEFFF",
      "info": "#0E6DAB",
      "warning-bg": "#FEEAC6",
      "warning": "#87620A",
      "success-bg": "#C2FFCD",
      "success": "#147B3A",
      "meter-low": "#D76158",
      "meter-mid": "#AB7E17",
      "meter-high": "#3C9756",
      "meter-pledged": "#918647",
      "meter-neutral": "#7D897E",
    },
  },
  {
    id: "yamuna",
    name: "Yamuna",
    family: "VIBRANT",
    description:
      "Deep river blue pushed to the edge of its chroma, cool and bright on any monitor near a window.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F7FAFF",
      "sunken": "#E3F0FF",
      "hairline": "#D4E7FE",
      "hairline-strong": "#B7D7FD",
      "ink": "#273342",
      "ink-secondary": "#4F5864",
      "ink-muted": "#626973",
      "ink-inverse": "#FCFEFF",
      "accent-bg": "#D6E8FE",
      "accent-border": "#ADD3FF",
      "accent": "#1474CC",
      "accent-hover": "#0364B4",
      "accent-text": "#0665B6",
      "focus-ring": "#1087EE",
      "danger-bg": "#FEE7E3",
      "danger": "#BD3832",
      "info-bg": "#DEEFFF",
      "info": "#0E6DAB",
      "warning-bg": "#FEEAC6",
      "warning": "#87620A",
      "success-bg": "#C2FFCD",
      "success": "#147B3A",
      "meter-low": "#D66057",
      "meter-mid": "#AA7D15",
      "meter-high": "#3B9555",
      "meter-pledged": "#908546",
      "meter-neutral": "#7C8691",
    },
  },
  {
    id: "jamun",
    name: "Jamun",
    family: "VIBRANT",
    description:
      "Ripe jamun purple, the most festive pack in the set and the one that reads as celebration.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FCF9FF",
      "sunken": "#F4E9FF",
      "hairline": "#EFDFFE",
      "hairline-strong": "#E4CAFB",
      "ink": "#372E3F",
      "ink-secondary": "#5B5461",
      "ink-muted": "#6C6671",
      "ink-inverse": "#FEFDFF",
      "accent-bg": "#EFDFFE",
      "accent-border": "#DFC0FD",
      "accent": "#9851CE",
      "accent-hover": "#230038",
      "accent-text": "#8940BC",
      "focus-ring": "#A967DE",
      "danger-bg": "#FEE7E3",
      "danger": "#BD3832",
      "info-bg": "#DEEFFF",
      "info": "#0E6DAB",
      "warning-bg": "#FEEAC6",
      "warning": "#87620A",
      "success-bg": "#C2FFCD",
      "success": "#147B3A",
      "meter-low": "#D55F56",
      "meter-mid": "#A97C13",
      "meter-high": "#3A9554",
      "meter-pledged": "#8F8445",
      "meter-neutral": "#88818E",
    },
  },
  {
    id: "peacock",
    name: "Peacock",
    family: "VIBRANT",
    description:
      "Peacock teal against warm-white paper, vivid without turning cold.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#ECFFFE",
      "sunken": "#C8FAF9",
      "hairline": "#B9EFEF",
      "hairline-strong": "#99E0E1",
      "ink": "#1F3838",
      "ink-secondary": "#4A5C5C",
      "ink-muted": "#5E6D6D",
      "ink-inverse": "#F8FFFF",
      "accent-bg": "#AAF6F7",
      "accent-border": "#78E4E5",
      "accent": "#0B7F81",
      "accent-hover": "#0E6E6F",
      "accent-text": "#037274",
      "focus-ring": "#069698",
      "danger-bg": "#FEE7E3",
      "danger": "#BD3832",
      "info-bg": "#DEEFFF",
      "info": "#0E6DAB",
      "warning-bg": "#FEEAC6",
      "warning": "#87620A",
      "success-bg": "#C2FFCD",
      "success": "#147B3A",
      "meter-low": "#D76258",
      "meter-mid": "#AB7F17",
      "meter-high": "#3D9757",
      "meter-pledged": "#918748",
      "meter-neutral": "#788989",
    },
  },
  {
    id: "clay-lamp",
    name: "Clay Lamp",
    family: "BALANCED",
    description:
      "Warm lamp-lit red, colourful in the way a room lit by ghee lamps is colourful.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FFF9F8",
      "sunken": "#FFECEB",
      "hairline": "#FBDEDD",
      "hairline-strong": "#F0CCC9",
      "ink": "#3F302F",
      "ink-secondary": "#625555",
      "ink-muted": "#726767",
      "ink-inverse": "#FFFDFD",
      "accent-bg": "#FFE3E1",
      "accent-border": "#FBC2BF",
      "accent": "#B95252",
      "accent-hover": "#320004",
      "accent-text": "#AB4547",
      "focus-ring": "#CE6968",
      "danger-bg": "#FFEBE8",
      "danger": "#B34740",
      "info-bg": "#E5F2FE",
      "info": "#116FAD",
      "warning-bg": "#FFEED0",
      "warning": "#89640E",
      "success-bg": "#CFFED8",
      "success": "#007D38",
      "meter-low": "#D76158",
      "meter-mid": "#AB7E17",
      "meter-high": "#3D9757",
      "meter-pledged": "#918748",
      "meter-neutral": "#938281",
    },
  },
  {
    id: "fern-courtyard",
    name: "Fern Courtyard",
    family: "BALANCED",
    description:
      "A calm garden green that keeps long inventory tables feeling fresh rather than clinical.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F6FDF5",
      "sunken": "#E7F6E5",
      "hairline": "#D8EAD6",
      "hairline-strong": "#C4DAC1",
      "ink": "#2E372D",
      "ink-secondary": "#545B53",
      "ink-muted": "#666C66",
      "ink-inverse": "#FBFFFB",
      "accent-bg": "#D9F3D6",
      "accent-border": "#BBDEB7",
      "accent": "#3A8234",
      "accent-hover": "#297222",
      "accent-text": "#2E7627",
      "focus-ring": "#54974D",
      "danger-bg": "#FFEBE8",
      "danger": "#B34740",
      "info-bg": "#E5F2FE",
      "info": "#116FAD",
      "warning-bg": "#FFEED0",
      "warning": "#89640E",
      "success-bg": "#CFFED8",
      "success": "#007D38",
      "meter-low": "#D86358",
      "meter-mid": "#AD7F18",
      "meter-high": "#3F9858",
      "meter-pledged": "#928849",
      "meter-neutral": "#80897E",
    },
  },
  {
    id: "cerulean-tile",
    name: "Cerulean Tile",
    family: "BALANCED",
    description:
      "Glazed tile blue, cool and orderly, made for people who read numbers all morning.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F2FDFE",
      "sunken": "#DCF7F9",
      "hairline": "#CCEAED",
      "hairline-strong": "#B4DBDE",
      "ink": "#263739",
      "ink-secondary": "#4F5C5C",
      "ink-muted": "#626D6E",
      "ink-inverse": "#FAFFFF",
      "accent-bg": "#C6F5F8",
      "accent-border": "#A0E0E5",
      "accent": "#177F86",
      "accent-hover": "#056E74",
      "accent-text": "#12737A",
      "focus-ring": "#19969F",
      "danger-bg": "#FFEBE8",
      "danger": "#B34740",
      "info-bg": "#E5F2FE",
      "info": "#116FAD",
      "warning-bg": "#FFEED0",
      "warning": "#89640E",
      "success-bg": "#CFFED8",
      "success": "#007D38",
      "meter-low": "#D86358",
      "meter-mid": "#AD7F18",
      "meter-high": "#3F9858",
      "meter-pledged": "#928849",
      "meter-neutral": "#798A8C",
    },
  },
  {
    id: "indigo-ledger",
    name: "Indigo Ledger",
    family: "BALANCED",
    description:
      "Indigo on cream, the register of a well-kept account book with a little colour in it.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F8FAFF",
      "sunken": "#EBF1FE",
      "hairline": "#DBE5FC",
      "hairline-strong": "#C8D4F1",
      "ink": "#2E3440",
      "ink-secondary": "#545962",
      "ink-muted": "#676A72",
      "ink-inverse": "#FDFEFF",
      "accent-bg": "#E1EBFF",
      "accent-border": "#BFD2FE",
      "accent": "#4F6FC3",
      "accent-hover": "#405FB1",
      "accent-text": "#4463B6",
      "focus-ring": "#6485D7",
      "danger-bg": "#FFEBE8",
      "danger": "#B34740",
      "info-bg": "#E5F2FE",
      "info": "#116FAD",
      "warning-bg": "#FFEED0",
      "warning": "#89640E",
      "success-bg": "#CFFED8",
      "success": "#007D38",
      "meter-low": "#D76258",
      "meter-mid": "#AB7F17",
      "meter-high": "#3D9757",
      "meter-pledged": "#928748",
      "meter-neutral": "#818693",
    },
  },
  {
    id: "plum-silk",
    name: "Plum Silk",
    family: "BALANCED",
    description:
      "Soft plum with a warm neutral cast, playful in the accents and sober in the surfaces.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FFF8FF",
      "sunken": "#FBEBFB",
      "hairline": "#F2DFF2",
      "hairline-strong": "#E5CDE4",
      "ink": "#3A303B",
      "ink-secondary": "#5D565D",
      "ink-muted": "#6E686E",
      "ink-inverse": "#FFFDFF",
      "accent-bg": "#FCE0FC",
      "accent-border": "#EAC4EA",
      "accent": "#A058A2",
      "accent-hover": "#29002C",
      "accent-text": "#924B94",
      "focus-ring": "#B36EB5",
      "danger-bg": "#FFEBE8",
      "danger": "#B34740",
      "info-bg": "#E5F2FE",
      "info": "#116FAD",
      "warning-bg": "#FFEED0",
      "warning": "#89640E",
      "success-bg": "#CFFED8",
      "success": "#007D38",
      "meter-low": "#D76158",
      "meter-mid": "#AB7E17",
      "meter-high": "#3C9656",
      "meter-pledged": "#918647",
      "meter-neutral": "#8D828D",
    },
  },
  {
    id: "terracotta",
    name: "Terracotta",
    family: "MUTED",
    description:
      "The house terracotta, quiet baked earth with colour reserved for status and the thing you can press.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FFFAF8",
      "sunken": "#FBF1EC",
      "hairline": "#EFE2DD",
      "hairline-strong": "#E1D1CA",
      "ink": "#3B3330",
      "ink-secondary": "#5F5856",
      "ink-muted": "#706A68",
      "ink-inverse": "#FFFEFD",
      "accent-bg": "#FFEAE0",
      "accent-border": "#ECD1C5",
      "accent": "#9A674F",
      "accent-hover": "#89573F",
      "accent-text": "#905D46",
      "focus-ring": "#B07D66",
      "danger-bg": "#FFEBE8",
      "danger": "#A84F47",
      "info-bg": "#E4F2FF",
      "info": "#1F6FA9",
      "warning-bg": "#FFEED0",
      "warning": "#89640F",
      "success-bg": "#D4FDDB",
      "success": "#2A7B43",
      "meter-low": "#D96359",
      "meter-mid": "#B07F00",
      "meter-high": "#3F9858",
      "meter-pledged": "#938849",
      "meter-neutral": "#93857F",
    },
  },
  {
    id: "sage-room",
    name: "Sage Room",
    family: "MUTED",
    description:
      "Pale sage neutrals for a kitchen office that wants to feel cool and uncluttered.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F8FDFA",
      "sunken": "#EDF6EF",
      "hairline": "#DCE8E0",
      "hairline-strong": "#C9D8CD",
      "ink": "#303733",
      "ink-secondary": "#565C58",
      "ink-muted": "#696D6A",
      "ink-inverse": "#FCFFFD",
      "accent-bg": "#E0F4E7",
      "accent-border": "#C6DFCE",
      "accent": "#4A7E5E",
      "accent-hover": "#3A6D4F",
      "accent-text": "#417455",
      "focus-ring": "#639475",
      "danger-bg": "#FFEBE8",
      "danger": "#A84F47",
      "info-bg": "#E4F2FF",
      "info": "#1F6FA9",
      "warning-bg": "#FFEED0",
      "warning": "#89640F",
      "success-bg": "#D4FDDB",
      "success": "#2A7B43",
      "meter-low": "#D9645A",
      "meter-mid": "#B08001",
      "meter-high": "#409959",
      "meter-pledged": "#94894A",
      "meter-neutral": "#7F8B83",
    },
  },
  {
    id: "slate-morning",
    name: "Slate Morning",
    family: "MUTED",
    description:
      "Blue-grey daylight, the most restrained of the coloured packs and the easiest on tired eyes.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#F8FCFF",
      "sunken": "#EBF5FB",
      "hairline": "#DBE6EE",
      "hairline-strong": "#C8D6E0",
      "ink": "#2F363B",
      "ink-secondary": "#565B5F",
      "ink-muted": "#686C6F",
      "ink-inverse": "#FDFEFF",
      "accent-bg": "#DFF2FE",
      "accent-border": "#C2DCEC",
      "accent": "#437898",
      "accent-hover": "#336887",
      "accent-text": "#3A6F8E",
      "focus-ring": "#5D8FAE",
      "danger-bg": "#FFEBE8",
      "danger": "#A84F47",
      "info-bg": "#E4F2FF",
      "info": "#1F6FA9",
      "warning-bg": "#FFEED0",
      "warning": "#89640F",
      "success-bg": "#D4FDDB",
      "success": "#2A7B43",
      "meter-low": "#D9645A",
      "meter-mid": "#B07F00",
      "meter-high": "#409959",
      "meter-pledged": "#93894A",
      "meter-neutral": "#7D8A93",
    },
  },
  {
    id: "mauve-ash",
    name: "Mauve Ash",
    family: "MUTED",
    description:
      "Ash neutrals with the faintest mauve breath, soft without going pink.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FCFBFF",
      "sunken": "#F5F1FB",
      "hairline": "#E8E3EF",
      "hairline-strong": "#D7D2E0",
      "ink": "#36343B",
      "ink-secondary": "#5B595F",
      "ink-muted": "#6C6A6F",
      "ink-inverse": "#FEFEFF",
      "accent-bg": "#F2EAFF",
      "accent-border": "#DBD3EB",
      "accent": "#7D6A9A",
      "accent-hover": "#6D5A89",
      "accent-text": "#73608F",
      "focus-ring": "#9281AF",
      "danger-bg": "#FFEBE8",
      "danger": "#A84F47",
      "info-bg": "#E4F2FF",
      "info": "#1F6FA9",
      "warning-bg": "#FFEED0",
      "warning": "#89640F",
      "success-bg": "#D4FDDB",
      "success": "#2A7B43",
      "meter-low": "#D96359",
      "meter-mid": "#B07F00",
      "meter-high": "#3F9858",
      "meter-pledged": "#938849",
      "meter-neutral": "#8A8593",
    },
  },
  {
    id: "graphite",
    name: "Graphite",
    family: "MUTED",
    description:
      "True monochrome greys with copper as the only accent and status the only other colour allowed.",
    palette: {
      "canvas": "#FFFFFF",
      "raised": "#FBFBFB",
      "sunken": "#F3F3F3",
      "hairline": "#E4E4E4",
      "hairline-strong": "#D4D4D4",
      "ink": "#353535",
      "ink-secondary": "#5A5A5A",
      "ink-muted": "#6B6B6B",
      "ink-inverse": "#FEFEFE",
      "accent-bg": "#FCEBDE",
      "accent-border": "#E8D3C4",
      "accent": "#966947",
      "accent-hover": "#855938",
      "accent-text": "#8B603E",
      "focus-ring": "#AC805F",
      "danger-bg": "#FFEBE8",
      "danger": "#A84F47",
      "info-bg": "#E4F2FF",
      "info": "#1F6FA9",
      "warning-bg": "#FDF1C2",
      "warning": "#7E690D",
      "success-bg": "#D4FDDB",
      "success": "#2A7B43",
      "meter-low": "#D96359",
      "meter-mid": "#9F8618",
      "meter-high": "#3F9958",
      "meter-pledged": "#878C4E",
      "meter-neutral": "#8B8688",
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
