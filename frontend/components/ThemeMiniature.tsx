import type { ThemePack } from "@/lib/theme-packs";

/**
 * A theme pack as a small picture of the application wearing it.
 *
 * <p><b>Why not swatches.</b> This replaced a row of eight coloured squares, and the squares
 * replaced a miniature before that, so it is worth being clear about what changed and what did not.
 *
 * <p>The squares were built because the first miniature carried a button labelled "Save", and
 * Rajeev pressed it, several times (2026-08-30). That objection was right and it still stands: a
 * decorative thing that looks operable is worse than a plain one, however much more it manages to
 * demonstrate. So there is no label on the button here, and nothing in this component is focusable
 * or announced — it is one `aria-hidden` picture inside the radio's own label.
 *
 * <p>What changed is that the squares stopped being able to tell the packs apart. THEME-TOKENS §5
 * is blunt about it: three of the eight chips were identical across all fifteen packs, so every
 * pack looked the same in the picker. And as of v2 the difference between the three families is
 * only partly colour — it is also material, the glossy, frosted and flat finishes of §6, which a
 * flat square cannot show at all. A gradient, a sheen, a corner radius and a shadow are only
 * visible on something shaped like the thing they will appear on.
 *
 * <p>So this is §5's own prescription: the pack's `canvas-bg` page, a `surface-card-bg` card on it
 * with a `table-header-bg` strip, one line of `ink` text, and one unlabelled `btn-primary`.
 *
 * <p><b>Inline styles rather than Tailwind classes</b>, and this is the one place in the codebase
 * where that is correct: a class resolves the custom properties on the document element, which is
 * to say the theme currently applied, and every pack in the picker would render identically.
 */
export function ThemeMiniature({ pack }: { pack: ThemePack }) {
  const s = pack.surfaces;

  return (
    <span
      aria-hidden="true"
      className="mt-3 block overflow-hidden p-2.5"
      style={{
        background: s["canvas-bg"],
        borderRadius: s["radius-card"],
        border: `1px solid ${pack.palette.hairline}`,
      }}
    >
      <span
        className="block overflow-hidden"
        style={{
          // §6: the sheen is a background *layer*, never an overlay. It is `none` in the frosted
          // and flat packs, where this collapses to the plain card background on its own. The band
          // is a percentage here rather than §4's 84px only because the whole picture is 76px tall.
          background: `${s["surface-card-sheen"]}, ${s["surface-card-bg"]}`,
          backgroundRepeat: "no-repeat, repeat",
          backgroundSize: "100% 60%, auto",
          border: s["surface-card-border"],
          borderRadius: s["radius-card"],
          boxShadow: s["shadow-card"],
          backdropFilter: s["surface-card-backdrop"],
        }}
      >
        {/* The header band. §3.1: painted with `table-header-bg`, never with `sunken` — a warm
            header on a cool page was, in the contract's own words, most of what looked broken. */}
        <span
          className="block px-2 py-1 text-[7px] font-semibold uppercase"
          style={{
            background: s["table-header-bg"],
            color: pack.palette["ink-secondary"],
            // §4's own .09em. Inline with the rest of the picture rather than as a utility: the
            // design system reserves the named `tracking-eyebrow` for real eyebrows on real
            // screens, and this is a 7px drawing of one.
            letterSpacing: "0.09em",
          }}
        >
          Item
        </span>

        <span className="flex items-center justify-between gap-2 px-2 py-2">
          <span className="text-[10px] leading-none" style={{ color: pack.palette.ink }}>
            Toor dal
          </span>
          {/* The primary button, and deliberately wordless. It is here to show the material — the
              gradient, the inset highlight, the corner radius the pack chose — and a button with a
              verb on it in the middle of a settings screen is a button somebody presses. */}
          <span
            className="block h-3.5 w-9"
            style={{
              background: s["btn-primary-bg"],
              border: s["btn-primary-border"],
              borderRadius: s["radius-control"],
              boxShadow: s["btn-primary-shadow"],
            }}
          />
        </span>
      </span>
    </span>
  );
}
