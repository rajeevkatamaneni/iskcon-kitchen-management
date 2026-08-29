import type { ThemePalette } from "@/lib/theme";

/**
 * A theme pack shown as a small piece of the application rather than as a row of swatches.
 *
 * <p>Swatches are the obvious way to show a palette and they answer the wrong question. Nobody
 * choosing a theme wants to know what eight colours it contains — they want to know what their
 * screen will look like, and in particular whether they will be able to read it. A strip of
 * squares cannot show that a label sits on a fill, that metadata is quieter than body text, or
 * that a status badge is legible on its own wash. Every one of those is a pairing, and a pairing
 * needs both halves shown together.
 *
 * <p>So this is a miniature: a page, a card raised off it, three weights of text, the primary
 * button with its label, and a status chip. Those five things are most of what any screen in this
 * application is made of, and between them they exercise nine of the twenty-three roles.
 *
 * <p>Inline styles, not Tailwind classes, and this is the one place in the codebase where that is
 * correct. A class here would read the custom properties on the document element — which is to say
 * the theme currently applied — so every card in the picker would render identically in whatever
 * pack is already active. The whole point is to draw a palette that is not the one in force.
 */
export function ThemePreview({ palette }: { palette: ThemePalette }) {
  return (
    <div
      aria-hidden="true"
      className="overflow-hidden rounded-sm border"
      style={{ backgroundColor: palette.canvas, borderColor: palette.hairline }}
    >
      <div className="flex flex-col gap-2 p-3">
        {/* A card raised off the page, which is what almost every screen here is built from. */}
        <div
          className="flex flex-col gap-1.5 rounded-sm p-2.5"
          style={{ backgroundColor: palette.raised }}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="text-sm font-semibold" style={{ color: palette.ink }}>
              Sunday feast
            </span>
            <span
              className="rounded-sm px-1.5 py-0.5 text-xs font-medium"
              style={{ backgroundColor: palette["success-bg"], color: palette.success }}
            >
              Recorded
            </span>
          </div>
          <span className="text-xs" style={{ color: palette["ink-secondary"] }}>
            420 servings, ready by noon
          </span>
          <span className="text-xs" style={{ color: palette["ink-muted"] }}>
            Kitchen 2, four volunteers
          </span>
        </div>

        <div className="flex items-center gap-2">
          <span
            className="rounded-sm px-2.5 py-1 text-xs font-medium"
            style={{ backgroundColor: palette.accent, color: palette["ink-inverse"] }}
          >
            Save
          </span>
          <span
            className="rounded-sm border px-2.5 py-1 text-xs font-medium"
            style={{
              backgroundColor: palette.canvas,
              borderColor: palette["accent-border"],
              color: palette["accent-text"],
            }}
          >
            Cancel
          </span>
          {/* The well an input sits in, so the quietest surface in the pack is on show too. */}
          <span
            className="h-6 flex-1 rounded-sm"
            style={{ backgroundColor: palette.sunken }}
          />
        </div>
      </div>
    </div>
  );
}
