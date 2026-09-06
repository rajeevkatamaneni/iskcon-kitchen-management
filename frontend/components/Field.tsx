import type { ReactNode } from "react";

import { InfoHint } from "@/components/ds/InfoHint";

/**
 * A labelled form field with its hint and its error message.
 *
 * <p>Label above rather than beside, always visible rather than a placeholder. Placeholder-only
 * labels vanish the moment someone types, which is exactly when a person filling in an
 * unfamiliar form most needs to check what they are answering.
 *
 * <p>The label and the error are indented by `field-inset` — the 13px that stands between an
 * input's outer edge and the first letter inside it. Set flush left they line up with the box
 * rather than with its contents, so a label floats 13px to the left of the very word it names. One
 * vertical line runs through the label, the value and the note about it.
 *
 * <p>The hint is the "i" beside the label rather than a line under the box (2026-09-04). Every
 * caller of this component inherits that at once, which is the point: a sentence of guidance under
 * every control is what made these forms tall enough that the button at the foot went unseen.
 * Anything a person must not miss — a warning that a secret is never shown again, a live value, a
 * server's own words — is not a hint and does not belong in this prop; it stays as visible text the
 * caller lays out itself.
 *
 * <p>Errors are wired to the input with aria-describedby and aria-invalid, so a screen reader
 * announces the problem rather than leaving it as red text nobody hears. The hint is wired by
 * {@link InfoHint}'s own tooltip while it is open, so it is not in this field's describedby.
 */
interface FieldProps {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  children: (props: {
    id: string;
    "aria-invalid": boolean;
    "aria-describedby": string | undefined;
    className: string;
  }) => ReactNode;
}

/** The one label style in the app: a step darker and a step heavier than the text around it. */
export const FIELD_LABEL = "pl-field-inset text-sm font-medium text-ink";
/**
 * The one style for a note that stays visible under a box — a warning, a live value, a preview.
 * Guidance is not one of these: it goes in the label's "i". Kept as the single style so the notes
 * that do earn their place still read the same on every screen.
 */
export const FIELD_HINT = "pl-field-inset text-sm text-ink-secondary";
/** The one field-error style. */
export const FIELD_ERROR = "pl-field-inset text-sm text-danger";

export function Field({ id, label, hint, error, required, children }: FieldProps) {
  const errorId = error ? `${id}-error` : undefined;

  return (
    <div>
      {/*
        The "i" sits beside the <label>, never inside it. A <label>'s control is its first labelable
        descendant, so a button within it becomes the labelled thing and the input loses its own
        name — the trap HintedField's render-function API exists to prevent, and the same one is
        open here because this component takes the id rather than minting it.
      */}
      <span className="flex items-center gap-1.5">
        <label htmlFor={id} className={`block ${FIELD_LABEL}`}>
          {label}
          {required && <span className="ml-1 text-ink-muted">(required)</span>}
        </label>
        {hint && <InfoHint text={hint} label={label} />}
      </span>

      <div className="mt-2">
        {children({
          id,
          "aria-invalid": Boolean(error),
          "aria-describedby": errorId,
          className: [
            // No fill and no fixed radius here. §4 paints a control with `input-bg` — which is
            // translucent in the frosted packs and needs the backdrop-filter that comes with it —
            // and rounds it to the pack's own `radius-control`. §6 is specific that a translucent
            // control over an un-blurred backdrop looks like a rendering bug, which is what
            // painting `bg-canvas` over the top would produce.
            "min-h-touch w-full rounded-control border px-3 text-base",
            "transition-colors duration-state",
            error ? "border-danger" : "border-hairline-strong",
          ].join(" "),
        })}
      </div>

      {error && (
        <p id={errorId} className={`mt-1.5 ${FIELD_ERROR}`}>
          {error}
        </p>
      )}
    </div>
  );
}
