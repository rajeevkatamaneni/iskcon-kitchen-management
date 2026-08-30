# Brief for Claude Design — theme packs

Copy everything below the line into Claude Design. It is written to be self-contained: it assumes
no knowledge of this codebase.

Two things make or break the result, and both are covered below — **the full list of 28 colour
roles** (the last handoff was missing three, and we had to invent them), and **the contrast
pairings stated up front** (the last handoff failed two of them, and one was a defect we had
already fixed once ourselves).

---

## The product

A web application for running the kitchens of ISKCON temples in India — recipes, inventory, meal
planning, purchasing, volunteer rotas and donations. It is used by temple administrators, kitchen
managers, cooks and volunteers.

Where it is used matters more than usual:

- **Bright kitchens and cheap monitors.** Screens are often near a window or under fluorescent
  light, on hardware nobody chose for its colour accuracy.
- **On phones, with wet or floury hands**, as often as on a laptop.
- **By people of every age**, including senior devotees. Roughly one man in twelve has some colour
  vision deficiency, so colour never carries meaning alone — every status also carries a word.

The interface is calm and dense: white or near-white page, cards raised off it by tone rather than
by borders, one accent colour, and status colour used strictly for status. No gradients. No
shadows except the focus ring. No transparency or blur.

## What we need

**Fifteen theme packs, in three groups of five.** A temple administrator picks one and it applies
to everyone who serves at that temple.

**Group 1 — very colourful, vibrant and shiny.** For the people who like their interface loud and
full of colour. Saturated, lively, cheerful, high energy. Push this as far as the contrast rules
below allow.

One caution about "shiny": this design system has **no gradients and no shadows** except the focus
ring, and no transparency or blur. So shine cannot come from gloss — it has to come from vivid,
saturated colour and from generous use of the pale accent washes. Please do not return gradient
specifications; we cannot use them.

**Group 2 — colourful, but not in your face.** These people like colour and do not want to be
shouted at. Moderately bright. Playful and sophisticated at the same time, which is the hard
combination and the reason this group is worth five packs of its own. Most temples will probably
end up here.

**Group 3 — soft and muted, for the minimalists.** Quiet, restrained neutrals. Colour appears only
as a pop, and only to mean something — a status, an alert, a notice, or the thing you can press.
Our existing terracotta palette sits squarely in this group and is a good reference for the
register.

**One of these five must be a true monochrome pack.** Greys throughout — surfaces, lines and text
all neutral — with colour breaking through in exactly three places: red and green for status,
copper or gold as the accent, and that same accent on a hover border or a button. Nothing else in
the pack should carry a hue at all.

### Two rules about distinctness

1. **Within a group, the five packs must differ in accent hue, not just in shading.** We were
   previously given four variants that shared one accent and differed only in the neutral cast, and
   in a picker they were indistinguishable — four cards that looked the same. Five genuinely
   different hues per group, please.
2. **Each pack should have a point of view** — a reason it looks the way it does, in one sentence.
   That sentence ships with it, under the name.

## The 28 colour roles

Every pack must supply all 28. Not 27. A pack missing one leaves that surface wearing the previous
theme's colour, which looks like a rendering bug and is very hard to trace.

### Surfaces — three tones, separated by tone rather than by borders

| Role | What it is |
|---|---|
| `canvas` | The page itself |
| `raised` | Cards, panels, the navigation sidebar |
| `sunken` | Input boxes, wells, table header rows, progress-bar tracks |

### Lines

| Role | What it is |
|---|---|
| `hairline` | A thin rule, used only where tone alone is not enough |
| `hairline-strong` | The same, emphasised — hover, dividers that must be seen |

### Text

| Role | What it is |
|---|---|
| `ink` | Body text and headings |
| `ink-secondary` | Supporting text, field labels |
| `ink-muted` | Metadata, placeholders, hints |
| `ink-inverse` | Text sitting *on* the accent — the primary button's label |

### Accent — one colour, three jobs: the primary action, the active menu item, the focus ring

| Role | What it is |
|---|---|
| `accent` | The fill of the primary button, and the active menu item |
| `accent-hover` | The same fill, one step darker, under the pointer |
| `accent-text` | The accent used as text — links, and the label on a secondary button |
| `accent-bg` | A pale wash of the accent, for a selected row or a tint badge |
| `accent-border` | A quiet border in the accent family, for a secondary button |
| `focus-ring` | The 3px ring around whatever has keyboard focus |

**`focus-ring` must be its own colour, not the same as `accent-border`.** Please do not skip this
one. Both palettes we have seen made the same mistake — pointing the focus ring at the pale border
colour, which measured 1.4:1 against the page. That is the only thing a keyboard user has to tell
them where they are, and at that ratio it is invisible. It needs to be a good deal darker than the
border, while still reading as part of the accent family.

### Status — never decorative

Each is a pair: a pale wash, and the ink written on it.

| Role | Meaning |
|---|---|
| `danger-bg` / `danger` | Something is wrong — a rejected delivery, an overdue invoice |
| `warning-bg` / `warning` | Something needs care — low stock, an under-staffed shift |
| `success-bg` / `success` | Something is done — paid, received, fully staffed |
| `info-bg` / `info` | Something is worth knowing — a fasting day on the calendar |

Three constraints here:

- **Red must read as wrong and green as done.** These are not a matter of taste and should stay
  recognisable in every pack, including the monochrome one.
- **Warning must not be mistakable for the accent.** If the accent is orange and the warning is
  also orange, a warning looks like a button to press. Gold, bronze or amber usually works.
- **In the monochrome pack**, status colour is the exception to the greys and should stay clearly
  red / green / gold.

### Progress meters — fills only, never text, never a background

| Role | What it is |
|---|---|
| `meter-low` | A bar that is nearly empty and should not be |
| `meter-mid` | Part way |
| `meter-high` | Full, or as good as |
| `meter-pledged` | Money promised towards something not yet bought |
| `meter-neutral` | A proportion that carries no judgement — a share of last month's spend |

These sit inside a `sunken` track.

## Contrast — please design to these, and check before sending

This is where the last handoff came unstuck. Every pairing below is one this interface actually
puts in front of somebody. **All 39 must hold, in all 15 packs.**

**4.5:1 minimum** (WCAG AA for body text):

- `ink` on `canvas`, on `raised`, on `sunken`
- `ink-secondary` on `canvas`, on `raised`, on `sunken`
- `ink-muted` on `canvas`, on `raised`, on `sunken` ← *the one most often missed. "Muted" tends to
  get chosen by eye and lands around 3:1. `sunken` is the hard one, because placeholder text sits
  inside input boxes.*
- `ink-inverse` on `accent`, and on `accent-hover`
- `accent-text` on `canvas`, on `raised`, on `accent-bg`
- `danger` on `danger-bg`, on `canvas`, on `raised`
- `warning` on `warning-bg`, on `canvas`, on `raised`
- `success` on `success-bg`, on `canvas`, on `raised`
- `info` on `info-bg`, on `canvas`, on `raised`

**3:1 minimum** (WCAG AA for interface components and focus indicators):

- `accent` on `canvas`, on `raised` — the button has to be findable before its label is read
- `focus-ring` on `canvas`, on `raised`, on `sunken`
- each of the five `meter-*` colours on `sunken` — you have to see where the bar ends

**Visible, though not a WCAG rule:**

- `hairline` on `canvas` at least 1.2:1
- `hairline-strong` on `canvas` at least 1.35:1
- `sunken` on `canvas` at least 1.05:1 — the surfaces separate by tone, so the step must exist

`accent-border` has no floor; it is deliberately quiet.

**One thing that constrains the bright group more than it looks.** The primary button is a solid
fill with `ink-inverse` on it, and that pair needs 4.5:1. So a pale bright colour — a light yellow,
a pastel cyan — cannot be the `accent`, because nothing white enough to be "inverse" can be read on
it. In a light interface, brightness has to come from **saturation, not lightness**. A vivid
saturated blue at mid lightness reads every bit as lively as a pale one and can actually carry its
label. Please put the brightness into chroma and into the washes, borders and focus rings, which
have no such obligation.

## What to send back

**One file, `theme-packs.json`, downloadable.** Exactly this shape:

```json
{
  "packs": [
    {
      "id": "marigold",
      "name": "Marigold",
      "group": "vibrant",
      "description": "One sentence saying what it feels like and why it looks this way.",
      "palette": {
        "canvas": "#FFFFFF",
        "raised": "#FFFBF3",
        "sunken": "#FBF0DC",
        "hairline": "#F0E2C8",
        "hairline-strong": "#E2CFA9",
        "ink": "#2A2318",
        "ink-secondary": "#5F5442",
        "ink-muted": "#736753",
        "ink-inverse": "#FFFDF8",
        "accent-bg": "#FDEFD2",
        "accent-border": "#F5D89A",
        "accent": "#9A6206",
        "accent-hover": "#7E4F03",
        "accent-text": "#8A5705",
        "focus-ring": "#BA800E",
        "danger-bg": "#FBE7E4",
        "danger": "#A32B1C",
        "info-bg": "#E6EFF7",
        "info": "#245C86",
        "warning-bg": "#F8EBD0",
        "warning": "#7E5A15",
        "success-bg": "#E3EFE5",
        "success": "#2E6B45",
        "meter-low": "#C4553F",
        "meter-mid": "#B07D1E",
        "meter-high": "#3B8B57",
        "meter-pledged": "#AF8339",
        "meter-neutral": "#8C8375"
      }
    }
  ]
}
```

Rules for the fields:

- `id` — lower case, digits and single hyphens only, matching `^[a-z0-9]+(-[a-z0-9]+)*$`. It is
  stored in our database and can never be renamed afterwards, so pick something durable.
- `group` — exactly one of `vibrant`, `balanced`, `muted`.
- `description` — one sentence, sentence case, no semicolons, no exclamation marks.
- `palette` — all 28 keys, every value a six-digit `#RRGGBB`. No shorthand, no named colours, no
  `rgb()`, no alpha.

**Please also include, in your reply but not in the file, a short table per pack of the five
tightest contrast pairings with their measured ratios.** It is how we will know the checking was
actually done, and it is quick for us to verify.
