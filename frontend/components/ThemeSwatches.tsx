import type { ThemePalette } from "@/lib/theme";

/**
 * A theme pack as a row of its colours.
 *
 * <p>This replaced a miniature of the interface — a little card with sample text, a status chip and
 * a pair of buttons. The miniature showed more, and it was the wrong thing to build: one of those
 * buttons said "Save", and Rajeev pressed it, several times, because a button that says Save in the
 * middle of a settings screen is a button you press (2026-08-30). A decorative thing that looks
 * operable is worse than a plain one, however much more it manages to demonstrate.
 *
 * <p>So: squares. Nothing here can be mistaken for a control, and the only control in the section
 * is the one that saves.
 *
 * <p>Inline styles rather than Tailwind classes, and this is the one place in the codebase where
 * that is correct: a class would read the custom properties on the document element, which is to
 * say the theme currently applied, and every pack in the picker would render identically.
 */

/** The roles worth showing, in the order a person meets them on a screen. */
const SHOWN: { token: keyof ThemePalette; label: string }[] = [
  { token: "accent", label: "Buttons and links" },
  { token: "accent-bg", label: "The selected menu item" },
  { token: "ink", label: "Text" },
  { token: "raised", label: "Cards" },
  { token: "sunken", label: "Input boxes" },
  { token: "success", label: "Done" },
  { token: "warning", label: "Careful" },
  { token: "danger", label: "Wrong" },
];

export function ThemeSwatches({ palette }: { palette: ThemePalette }) {
  return (
    <span aria-hidden="true" className="mt-3 flex gap-1.5">
      {SHOWN.map(({ token, label }) => (
        <span
          key={token}
          title={label}
          className="h-7 w-7 flex-1 rounded-sm border"
          style={{
            backgroundColor: palette[token],
            // A pale swatch on a pale card needs an edge, or it is a gap rather than a colour.
            borderColor: palette.hairline,
          }}
        />
      ))}
    </span>
  );
}
