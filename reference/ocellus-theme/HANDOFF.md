# Ocellus theme — handoff

Indigo peacock-eye palette for the ISKCON Kitchen design system. One file, same token
names as the existing `tokens/colors.css`, so applying it is a swap and not a refactor.

## Apply it

Replace the contents of the design system's `tokens/colors.css` with `colors.css` from this
folder. Nothing else changes: type, spacing, radii, motion and layout tokens stay as they are.

If the app reads colours from `tailwind.config.ts` rather than CSS variables, map the values
across by token name — every name in `colors.css` matches the existing one.

## What the tokens mean

- `--canvas` / `--raised` / `--sunken` — the only three surface tones. Max two on a screen.
- `--hairline` — used only where tone alone isn't enough (table rows, public header).
- `--accent` — indigo `#3B3A8F`, and it has exactly three jobs: primary action, active nav
  item, focus ring. Never a heading colour, never a chart series, never a decorative fill.
- `--accent-bg` — the pale wash for selected states, used with semibold weight.
- Status tokens are status only. The warning is bronze, deliberately not near the accent hue,
  so a warning can never be mistaken for something to click.
- Meter tokens are fills, never text or backgrounds.

No gradients, no shadows other than the focus ring, no transparency or blur — unchanged from
the existing system.

## Variants

The bottom of `colors.css` carries four commented override blocks that keep the indigo accent
and shift the second hue and the neutral cast: green barbule, copper, pewter, and full plume.
Paste one block after `:root` to use it.

## Checks worth running after the swap

- Primary button text on `--accent` at 16px: contrast is 8.9:1.
- `--accent-text` on `--canvas` for links: 9.7:1.
- `--ink-secondary` on `--raised` for metadata: 6.4:1.
- Active nav item is `--accent-bg` fill plus weight 600, not a coloured left border.
